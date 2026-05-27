package ink.siilm.gateway.http

import ink.siilm.core.auth.*
import ink.siilm.core.config.ServerConfigManager
import ink.siilm.core.file.FileMetaManager
import ink.siilm.core.file.FileResolver
import ink.siilm.core.message.ChatHistoryService
import ink.siilm.core.room.*
import ink.siilm.gateway.auth.JwtValidator
import ink.siilm.gateway.codec.JsonCodec
import ink.siilm.gateway.file.FileChannelOps
import ink.siilm.gateway.model.http.response.*
import ink.siilm.gateway.model.http.response.MemberDetail
import ink.siilm.gateway.ws.WebSocketSessionManager
import ink.siilm.shared.bridge.CoreBridge
import ink.siilm.shared.config.LandropProperties
import ink.siilm.shared.config.ServerConfig
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.jvm.javaio.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Path
import ink.siilm.gateway.model.common.RoomInfo as RoomInfoDto

class HttpApiRoutes(
    private val config: ServerConfig,
    private val sessionManager: WebSocketSessionManager,
    private val fileChannelOps: FileChannelOps,
    private val jsonCodec: JsonCodec,
    private val bridge: CoreBridge,
    private val authService: AuthService,
    private val roomManager: RoomManager,
    private val roomJoinService: RoomJoinService,
    private val permissionService: PermissionService,
    private val chatHistoryService: ChatHistoryService,
    private val serverConfigManager: ServerConfigManager,
    private val jwtValidator: JwtValidator,
    private val storageAllocator: ink.siilm.core.file.StoragePathAllocator,
    private val eventService: ink.siilm.core.event.EventService
) {
    private val apiJson = Json { explicitNulls = false; encodeDefaults = true }
    private val fileMetaManager by lazy { FileMetaManager(storageAllocator) }
    fun Routing.configureRouting() {
        // 公开路由（无需 JWT）
        authLogin(); authVerify(); authRefresh()
        healthCheck(); serverStatus()

        // 鉴权路由（需要 JWT access_token）
        authenticate("jwt-auth") {
            authRegister(); authLogout(); authRenameUsername(); whoami()
            createRoom(); roomsMine(); roomsAll(); forceJoin(); roomMembers(); roomSetDisplayName(); joinRoom()
            dissolveRoom(); kickMember(); muteMember(); unmuteMember() 
            roomMessages(); editMessage(); otherDisplayName()
            announceRoom(); inviteToRoom(); myReceivedInvites(); myInvites(); listInvites(); approveInvite(); rejectInvite()
            myJoinRequests(); listJoinRequests(); approveJoin(); rejectJoin()
            getConfig(); setConfig()
            listPublicAdmins(); appointPublicAdmin(); removePublicAdmin()
            promoteMember(); demoteMember(); deactivateUser(); activateUser(); banUser()
            fileUpload(); fileCheck(); fileCheckVerify(); fileDownload(); avatarUpload(); chatImageUpload(); roomFilesList(); roomFileDelete(); getFiles()
        }
    }

    private fun getUserId(call: ApplicationCall): String? {
        val header = call.request.authorization() ?: return null
        if (!header.startsWith("Bearer ")) return null
        return jwtValidator.validate(header.removePrefix("Bearer "))?.userId
    }
    private fun requireAuth(call: ApplicationCall) = getUserId(call) ?: throw AuthenticationException()
    private fun isOwner(userId: String) = permissionService.isOwner(userId)
    private fun isPublicAdmin(userId: String) = permissionService.isPublicAdmin(userId)
    private fun getUserRole(call: ApplicationCall): String? {
        val header = call.request.authorization() ?: return null
        if (!header.startsWith("Bearer ")) return null
        return jwtValidator.validate(header.removePrefix("Bearer "))?.globalRole
    }

    private fun Routing.authRegister() {
        post("/api/auth/register") {
            val operatorId = requireAuth(call)
            if (!isOwner(operatorId) && !isPublicAdmin(operatorId)) {
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "owner_or_public_admin_required"))
                return@post
            }
            val body = call.receive<Map<String, String>>()
            val username = body["username"] ?: ""
            val userId = body["user_id"]?.takeIf { it.isNotBlank() }
            if (username.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "username is required"))
                return@post
            }
            if (username.length > 25) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "username max 25 characters"))
                return@post
            }
            if (userId != null && !userId.matches(Regex("^[A-Za-z0-9]{12}$"))) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "user_id must be 12 alphanumeric characters"))
                return@post
            }
            when (val r = authService.registerUser(username, userId)) {
                is RegisterResult.Success -> {
                    // 将密钥写入文件系统
                    val secretsDir = java.io.File(LandropProperties.getSecretsDir(), r.userId)
                    secretsDir.mkdirs()
                    java.io.File(secretsDir, "${r.userId}.key").writeText(r.privateKeyBase64)
                    java.io.File(secretsDir, "${r.userId}.pub").writeText(r.publicKeyBase64)
                    log.info("User key files written: userId={}, dir={}", r.userId, secretsDir.absolutePath)

                    roomManager.joinRoom("PUBLIC", r.userId)
                    call.respond(HttpStatusCode.Created, mapOf("user_id" to r.userId, "username" to r.username, "global_role" to r.globalRole))
                }
                is RegisterResult.InvalidUserId -> call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_user_id_format"))
                is RegisterResult.UsernameTooLong -> call.respond(HttpStatusCode.BadRequest, mapOf("error" to "username_too_long"))
                is RegisterResult.UserIdAlreadyExists -> call.respond(HttpStatusCode.Conflict, mapOf("error" to "user_id_collision_retry"))
            }
        }
    }

    private fun Routing.authLogin() {
        post("/api/auth/login") {
            val body = call.receive<Map<String, String>>()
            val userId = body["user_id"] ?: ""
            when (val r = authService.initiateLogin(userId)) {
                is LoginInitiateResult.Success -> call.respond(mapOf("temp_session_id" to r.tempSessionId, "challenge" to r.challenge))
                is LoginInitiateResult.UserNotFound -> call.respond(HttpStatusCode.NotFound, mapOf("error" to "user_not_found"))
                is LoginInitiateResult.UserInactive -> call.respond(HttpStatusCode.Forbidden, mapOf("error" to "user_inactive"))
            }
        }
    }

    private fun Routing.authVerify() {
        post("/api/auth/verify") {
            val body = call.receive<Map<String, String>>()
            val tempSessionId = body["temp_session_id"] ?: ""
            val signature = body["signature"] ?: ""
            val deviceInfo = body["device_info"] ?: "{}"
            when (val r = authService.verifySignature(tempSessionId, signature, deviceInfo)) {
                is VerifyResult.Success -> call.respond(mapOf("access_token" to r.accessToken, "refresh_token" to r.refreshToken, "expires_in" to r.expiresIn.toString(), "refresh_expires_in" to r.refreshExpiresIn.toString(), "user_id" to r.userId, "username" to r.username, "global_role" to r.globalRole))
                is VerifyResult.InvalidSignature -> call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "invalid_signature"))
                is VerifyResult.ChallengeExpired -> call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "challenge_expired"))
                else -> call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "auth_failed"))
            }
        }
    }

    private fun Routing.authRefresh() {
        post("/api/auth/refresh") {
            val body = call.receive<Map<String, String>>()
            val refreshToken = body["refresh_token"] ?: ""
            when (val r = authService.refreshAccessToken(refreshToken)) {
                is RefreshResult.Success -> call.respond(mapOf("access_token" to r.accessToken, "expires_in" to r.expiresIn.toString()))
                is RefreshResult.TokenExpired -> call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "refresh_token_expired"))
                is RefreshResult.TokenRevoked -> call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "token_revoked"))
                else -> call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "invalid_token"))
            }
        }
    }

    private fun Routing.authLogout() {
        post("/api/auth/logout") {
            val userId = requireAuth(call)
            val body = call.receive<Map<String, String>>()
            val sessionId = body["session_id"]
            authService.revokeSession(userId, sessionId)
            call.respond(mapOf("status" to "logged_out"))
        }
    }

    private fun Routing.authRenameUsername() {
        put("/api/auth/rename_username") {
            val userId = requireAuth(call)
            val body = call.receive<Map<String, String>>()
            val newUsername = body["new_username"] ?: ""
            if (newUsername.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing new_username"))
                return@put
            }
            when (val r = authService.renameUsername(userId, newUsername)) {
                is RenameUsernameResult.Success -> call.respond(mapOf("username" to r.newUsername))
                is RenameUsernameResult.UsernameTooLong -> call.respond(HttpStatusCode.BadRequest, mapOf("error" to "username_too_long", "message" to "username max 25 chars"))
                is RenameUsernameResult.UserNotFound -> call.respond(HttpStatusCode.NotFound, mapOf("error" to "user_not_found"))
            }
        }
    }

    private fun Routing.whoami() {
        get("/api/auth/whoami") {
            val userId = requireAuth(call)
            val role = permissionService.getGlobalRole(userId) ?: "member"
            call.respond(mapOf("user_id" to userId, "role" to role))
        }
    }

    private fun Routing.healthCheck() { get("/api/health") { call.respondText("""{"status":"ok"}""", ContentType.Application.Json) } }
    private fun Routing.serverStatus() { get("/api/status") { requireAuth(call); call.respondText(apiJson.encodeToString(ServerStatusResponse(online = sessionManager.activeCount().toString())), ContentType.Application.Json) } }

    private fun Routing.roomsMine() {
        get("/api/rooms/mine") {
            val userId = requireAuth(call)
            val rooms = roomManager.listRoomsForUser(userId)
            val response = RoomListResponse(
                count = rooms.size.toString(),
                rooms = rooms.map { RoomInfoDto(it.roomId, it.roomName, it.memberCount.toString(), it.hasPassword) }
            )
            call.respondText(apiJson.encodeToString(response), ContentType.Application.Json)
        }
    }

    private fun Routing.roomsAll() {
        get("/api/rooms/all") {
            val role = getUserRole(call) ?: throw AuthenticationException()
            if (role != "owner" && role != "public_admin") {
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "admin_required"))
                return@get
            }
            // DEPRECATED buildJsonObject: replaced by data class
            val rooms = roomManager.listRooms()
            call.respondText(apiJson.encodeToString(
                RoomListResponse(count = rooms.size.toString(), rooms = rooms.map { RoomInfoDto(it.roomId, it.roomName, it.memberCount.toString(), it.hasPassword) })
            ), ContentType.Application.Json)
            /* DEPRECATED:
            call.respondText(buildJsonObject { ... rooms.forEach { ... } }.toString(), ...)
            */
        }
    }

    private fun Routing.forceJoin() {
        post("/api/rooms/{roomId}/force-join") {
            val operatorId = requireAuth(call)
            if (!isOwner(operatorId) && !isPublicAdmin(operatorId)) {
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "owner_or_public_admin_required"))
                return@post
            }
            val roomId = call.parameters["roomId"] ?: ""
            val body = call.receive<Map<String, String>>()
            val targetUserId = body["user_id"] ?: ""
            if (roomId.isBlank() || targetUserId.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "roomId and user_id required"))
                return@post
            }
            val ok = roomManager.forceJoinRoom(roomId, targetUserId)
            if (ok) call.respond(mapOf("status" to "joined"))
            else call.respond(HttpStatusCode.NotFound, mapOf("error" to "room_not_found_or_inactive"))
        }
    }

    private fun Routing.createRoom() {
        post("/api/rooms/create") {
            val userId = requireAuth(call)
            val body = call.receive<Map<String, String>>()
            val name = body["name"] ?: ""
            val roomType = body["room_type"]?.toIntOrNull() ?: 1
            val result = roomManager.createRoom(name, userId, roomType)
            result.fold(
                onSuccess = { call.respond(HttpStatusCode.Created, mapOf("room_id" to it.roomId, "name" to it.roomName)) },
                onFailure = { call.respond(HttpStatusCode.Forbidden, mapOf("error" to "quota_exceeded", "message" to it.message)) }
            )
        }
    }

    private fun Routing.roomMembers() {
        get("/api/rooms/{roomId}/members") {
            requireAuth(call)
            val roomId = call.parameters["roomId"] ?: ""
            // DEPRECATED buildJsonObject: replaced by data class
            val members = roomManager.getMemberDetails(roomId)
            call.respondText(apiJson.encodeToString(
                RoomMembersResponse(roomId = roomId, count = members.size.toString(), members = members.map { MemberDetail(it.userId, it.displayName, username = it.username, avatarUrl = FileResolver.resolveAvatarUrl(it.avatarUrl, it.userId), role = it.role.toString(), muted = it.muted) })
            ), ContentType.Application.Json)
            /* DEPRECATED:
            call.respondText(buildJsonObject { ... members.forEach { ... } }.toString(), ...)
            */
        }
    }

    private fun Routing.roomSetDisplayName() {
        put("/api/rooms/{roomId}/display_name") {
            val userId = requireAuth(call)
            val roomId = call.parameters["roomId"] ?: ""
            val body = call.receive<Map<String, String>>()
            val displayName = body["display_name"] ?: ""
            if (displayName.isBlank()) {
                call.respondText(apiJson.encodeToString(ErrorResponse(error = "Missing display_name")), ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@put
            }
            if (permissionService.getRoleLevel(userId, roomId) < 1) {
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "admin_required"))
                return@put
            }
            val success = roomManager.setDisplayName(roomId, userId, displayName)
            if (success) {
                call.respondText(apiJson.encodeToString(DisplayNameResponse(success = true, displayName = displayName)), ContentType.Application.Json)
            } else {
                call.respondText(apiJson.encodeToString(ErrorResponse(error = "not_member", message = "User not in this room")), ContentType.Application.Json, HttpStatusCode.NotFound)
            }
        }
    }

    private fun Routing.joinRoom() {
        post("/api/rooms/{roomId}/join") {
            val userId = requireAuth(call)
            val roomId = call.parameters["roomId"] ?: ""
            val body = call.receive<Map<String, String>>()
            val message = body["message"]
            when (val r = roomJoinService.joinRoom(roomId, userId, message)) {
                JoinResult.Joined -> call.respond(mapOf("status" to "joined", "room_id" to roomId))
                is JoinResult.Pending -> {
                    call.respond(HttpStatusCode.Accepted, mapOf("status" to "pending"))
                    // 通知房间管理员
                    val notification = """{"type":"join_request","event_id":"${r.eventId}","room_id":"$roomId","applicant_id":"$userId"}"""
                    kotlinx.coroutines.runBlocking {
                        sessionManager.notifyRoomAdmins(roomId, notification)
                    }
                }
                JoinResult.AlreadyMember -> call.respond(HttpStatusCode.Conflict, mapOf("error" to "already_member"))
                else -> call.respond(HttpStatusCode.NotFound, mapOf("error" to "room_not_found"))
            }
        }
    }

    private fun Routing.myJoinRequests() {
        get("/api/rooms/join-requests/mine") {
            val userId = requireAuth(call)
            val requests = roomJoinService.getMyJoinRequests(userId)
            call.respond(requests.map {
                mapOf(
                    "id" to it.id.toString(),
                    "room_id" to it.roomId,
                    "applicant_id" to it.applicantId,
                    "message" to (it.message ?: ""),
                    "applied_at" to it.appliedAt.toString(),
                    "expires_at" to (it.expiresAt?.toString() ?: "")
                )
            })
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 房间管理：删除/踢人/禁言/公告/邀请
    // ═══════════════════════════════════════════════════════════

    private fun Routing.dissolveRoom() {
        delete("/api/rooms/{roomId}") {
            val operatorId = requireAuth(call)
            val roomId = call.parameters["roomId"] ?: ""
            if (!permissionService.canDissolveRoom(operatorId, roomId)) {
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "forbidden"))
                return@delete
            }
            roomManager.dissolveRoom(roomId, operatorId).fold(
                onSuccess = { call.respond(mapOf("status" to "dissolved")) },
                onFailure = { call.respond(HttpStatusCode.NotFound, mapOf<String, String>("error" to (it.message ?: "room_not_found"))) }
            )
        }
    }

    private fun Routing.kickMember() {
        delete("/api/rooms/{roomId}/members/{userId}") {
            val operatorId = requireAuth(call)
            val roomId = call.parameters["roomId"] ?: ""
            val targetUserId = call.parameters["userId"] ?: ""
            if (!permissionService.canManageMember(operatorId, roomId)) {
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "forbidden"))
                return@delete
            }
            roomManager.kickMember(roomId, targetUserId)
            call.respond(mapOf("status" to "kicked"))
        }
    }

    private fun Routing.muteMember() {
        put("/api/rooms/{roomId}/members/{userId}/mute") {
            val operatorId = requireAuth(call)
            val roomId = call.parameters["roomId"] ?: ""
            val targetUserId = call.parameters["userId"] ?: ""
            if (!permissionService.canManageMember(operatorId, roomId)) {
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "forbidden"))
                return@put
            }
            val ok = roomManager.muteMember(roomId, targetUserId)
            if (ok) call.respond(mapOf("status" to "muted"))
            else call.respond(HttpStatusCode.NotFound, mapOf("error" to "not_member"))
        }
    }

    private fun Routing.unmuteMember() {
        delete("/api/rooms/{roomId}/members/{userId}/mute") {
            val operatorId = requireAuth(call)
            val roomId = call.parameters["roomId"] ?: ""
            val targetUserId = call.parameters["userId"] ?: ""
            if (!permissionService.canManageMember(operatorId, roomId)) {
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "forbidden"))
                return@delete
            }
            val ok = roomManager.unmuteMember(roomId, targetUserId)
            if (ok) call.respond(mapOf("status" to "unmuted"))
            else call.respond(HttpStatusCode.NotFound, mapOf("error" to "not_member"))
        }
    }

    private fun Routing.announceRoom() {
        post("/api/rooms/{roomId}/announce") {
            val userId = requireAuth(call)
            val roomId = call.parameters["roomId"] ?: ""
            if (!permissionService.canBroadcast(userId, roomId)) {
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "permission_denied"))
                return@post
            }
            val body = call.receive<Map<String, String>>()
            val content = body["content"] ?: ""
            if (content.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing_content"))
                return@post
            }
            // 公告以系统消息形式存入 chat_messages
            val messageId = java.util.UUID.randomUUID().toString()
            val displayName = roomManager.getMemberDetails(roomId).find { it.userId == userId }?.displayName ?: userId
            val envelope = ink.siilm.proto.InternalComm.Envelope.newBuilder()
                .setMessageId(messageId)
                .setTimestamp(System.currentTimeMillis())
                .setMessageDelivery(
                    ink.siilm.proto.InternalComm.MessageDelivery.newBuilder()
                        .setFromUserId(userId)
                        .setFromUsername(displayName)
                        .setTextContent("[公告] $content")
                        .setContentType(ink.siilm.proto.InternalComm.MessageDelivery.ContentType.TEXT)
                        .also { it.roomId = roomId }
                )
                .build()
            bridge.sendToCore(envelope)
            call.respond(mapOf("message_id" to messageId, "status" to "sent"))
        }
    }

    private fun Routing.inviteToRoom() {
        post("/api/rooms/{roomId}/invite") {
            val inviterId = requireAuth(call)
            val roomId = call.parameters["roomId"] ?: ""
            val body = call.receive<Map<String, List<String>>>()
            val userIds = body["user_ids"] ?: emptyList()
            if (userIds.isEmpty()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing_user_ids"))
                return@post
            }
            val results = mutableListOf<Map<String, String>>()
            for (targetUserId in userIds) {
                val status = when (val r = roomJoinService.inviteToRoom(roomId, inviterId, targetUserId)) {
                    is InviteResult.Success -> {
                        val notification = """{"type":"invite_notify","event_id":"${r.eventId}","room_id":"$roomId","inviter_id":"$inviterId"}"""
                        kotlinx.coroutines.runBlocking {
                            sessionManager.sendToUser(targetUserId, notification)
                        }
                        "ok"
                    }
                    InviteResult.RoomNotFound -> "room_not_found"
                    InviteResult.NotMember -> "inviter_not_member"
                    InviteResult.AlreadyMember -> "already_member"
                    InviteResult.AlreadyInvited -> "already_invited"
                    else -> "unknown"
                }
                results.add(mapOf("user_id" to targetUserId, "status" to status))
            }
            call.respond(mapOf("invites" to results))
        }
    }

    private fun Routing.myInvites() {
        get("/api/rooms/invites/mine") {
            val userId = requireAuth(call)
            val invites = roomJoinService.getMyInvites(userId)
            call.respond(invites.map {
                mapOf(
                    "id" to it.id.toString(),
                    "room_id" to it.roomId,
                    "inviter_id" to it.inviterId,
                    "invitee_id" to it.inviteeId,
                    "requested_at" to it.requestedAt.toString(),
                    "expires_at" to (it.expiresAt?.toString() ?: "")
                )
            })
        }
    }

    private fun Routing.myReceivedInvites() {
        get("/api/rooms/invites/to-me") {
            val userId = requireAuth(call)
            val invites = roomJoinService.getMyReceivedInvites(userId)
            call.respond(invites.map {
                mapOf(
                    "id" to it.id.toString(),
                    "room_id" to it.roomId,
                    "inviter_id" to it.inviterId,
                    "invitee_id" to it.inviteeId,
                    "requested_at" to it.requestedAt.toString(),
                    "expires_at" to (it.expiresAt?.toString() ?: "")
                )
            })
        }
    }

    private fun Routing.listInvites() {
        get("/api/rooms/{roomId}/invites") {
            val userId = requireAuth(call)
            val roomId = call.parameters["roomId"] ?: ""
            if (!permissionService.canManageMember(userId, roomId)) {
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "forbidden"))
                return@get
            }
            val invites = roomJoinService.getPendingInvites(roomId)
            call.respond(invites.map { mapOf("id" to it.id.toString(), "room_id" to it.roomId, "inviter_id" to it.inviterId, "invitee_id" to it.inviteeId, "requested_at" to it.requestedAt.toString(), "expires_at" to (it.expiresAt?.toString() ?: "")) })
        }
    }

    private fun Routing.approveInvite() {
        put("/api/rooms/{roomId}/invites/{inviteId}/approve") {
            val reviewerId = requireAuth(call)
            val roomId = call.parameters["roomId"] ?: ""
            val inviteId = call.parameters["inviteId"]?.toIntOrNull() ?: 0
            if (!permissionService.canManageMember(reviewerId, roomId)) {
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "forbidden"))
                return@put
            }
            when (val r = roomJoinService.approveInvite(roomId, inviteId, reviewerId)) {
                ApproveResult.Success -> call.respond(mapOf("status" to "approved"))
                is ApproveResult.ReferredToAdmin -> {
                    call.respond(mapOf("status" to "referred", "event_id" to r.eventId))
                    // 通知房间管理员
                    val notification = """{"type":"join_request","event_id":"${r.eventId}","room_id":"$roomId","applicant_id":"$reviewerId"}"""
                    kotlinx.coroutines.runBlocking {
                        sessionManager.notifyRoomAdmins(roomId, notification)
                    }
                }
                ApproveResult.RequestNotFound -> call.respond(HttpStatusCode.NotFound, mapOf("error" to "invite_not_found"))
                ApproveResult.AlreadyProcessed -> call.respond(HttpStatusCode.Conflict, mapOf("error" to "already_processed"))
            }
        }
    }

    private fun Routing.rejectInvite() {
        put("/api/rooms/{roomId}/invites/{inviteId}/reject") {
            val reviewerId = requireAuth(call)
            val roomId = call.parameters["roomId"] ?: ""
            val inviteId = call.parameters["inviteId"]?.toIntOrNull() ?: 0
            if (!permissionService.canManageMember(reviewerId, roomId)) {
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "forbidden"))
                return@put
            }
            when (val r = roomJoinService.rejectInvite(roomId, inviteId, reviewerId)) {
                ApproveResult.Success -> call.respond(mapOf("status" to "rejected"))
                ApproveResult.RequestNotFound -> call.respond(HttpStatusCode.NotFound, mapOf("error" to "invite_not_found"))
                ApproveResult.AlreadyProcessed -> call.respond(HttpStatusCode.Conflict, mapOf("error" to "already_processed"))
                else -> {}
            }
        }
    }

    private fun Routing.listJoinRequests() {
        get("/api/rooms/{roomId}/join-requests") {
            val reviewerId = requireAuth(call)
            val roomId = call.parameters["roomId"] ?: ""
            if (!permissionService.canManageMember(reviewerId, roomId)) {
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "forbidden"))
                return@get
            }
            val requests = roomJoinService.getPendingJoinRequests(roomId)
            call.respond(requests.map {
                mapOf("id" to it.id.toString(), "room_id" to it.roomId, "applicant_id" to it.applicantId,
                    "message" to (it.message ?: ""), "applied_at" to it.appliedAt.toString(),
                    "expires_at" to it.expiresAt.toString())
            })
        }
    }

    private fun Routing.approveJoin() {
        put("/api/rooms/{roomId}/join-requests/{requestId}/approve") {
            val reviewerId = requireAuth(call)
            val roomId = call.parameters["roomId"] ?: ""
            val requestId = call.parameters["requestId"]?.toIntOrNull() ?: 0
            if (!permissionService.canManageMember(reviewerId, roomId)) {
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "forbidden"))
                return@put
            }
            when (val r = roomJoinService.approveJoin(roomId, requestId, reviewerId)) {
                ApproveResult.Success -> {
                    call.respond(mapOf("status" to "approved"))
                    // 通知其他管理员该申请已被处理
                    val notification = """{"type":"join_request_handled","room_id":"$roomId","action":"approved","by":"$reviewerId"}"""
                    kotlinx.coroutines.runBlocking {
                        sessionManager.notifyRoomAdmins(roomId, notification, excludeUserId = reviewerId)
                    }
                }
                ApproveResult.RequestNotFound -> call.respond(HttpStatusCode.NotFound, mapOf("error" to "request_not_found"))
                ApproveResult.AlreadyProcessed -> call.respond(HttpStatusCode.Conflict, mapOf("error" to "already_processed"))
                else -> {}
            }
        }
    }

    private fun Routing.rejectJoin() {
        put("/api/rooms/{roomId}/join-requests/{requestId}/reject") {
            val reviewerId = requireAuth(call)
            val roomId = call.parameters["roomId"] ?: ""
            val requestId = call.parameters["requestId"]?.toIntOrNull() ?: 0
            if (!permissionService.canManageMember(reviewerId, roomId)) {
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "forbidden"))
                return@put
            }
            when (val r = roomJoinService.rejectJoin(roomId, requestId, reviewerId)) {
                ApproveResult.Success -> call.respond(mapOf("status" to "rejected"))
                ApproveResult.RequestNotFound -> call.respond(HttpStatusCode.NotFound, mapOf("error" to "request_not_found"))
                ApproveResult.AlreadyProcessed -> call.respond(HttpStatusCode.Conflict, mapOf("error" to "already_processed"))
                else -> {}
            }
        }
    }

    private fun Routing.roomMessages() {
        get("/api/rooms/{roomId}/messages") {
            requireAuth(call)
            val roomId = call.parameters["roomId"] ?: ""
            val before = call.request.queryParameters["before"]?.toLongOrNull() ?: Long.MAX_VALUE
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
            // DEPRECATED buildJsonObject: replaced by data class
            val msgs = chatHistoryService.getRoomMessages(roomId, before, limit)
            val jsonParser = Json { ignoreUnknownKeys = true; isLenient = true }
            call.respondText(apiJson.encodeToString(
                RoomMessagesResponse(
                    roomId = roomId,
                    count = msgs.size.toString(),
                    messages = msgs.map { msg ->
                        MessageEntry(msg.messageId, msg.fromUserId, jsonParser.parseToJsonElement(msg.elements), msg.status, msg.createdAt.toString())
                    }
                )
            ), ContentType.Application.Json)
            /* DEPRECATED:
            call.respondText(buildJsonObject { ... msgs.forEach { ... } }.toString(), ...)
            */
        }
    }

    private fun Routing.editMessage() {
        put("/api/messages/{messageId}") {
            val userId = requireAuth(call)
            val messageId = call.parameters["messageId"] ?: ""
            val body = call.receive<Map<String, String>>()
            val elements = body["elements"] ?: ""
            if (elements.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing elements"))
                return@put
            }
            if (!permissionService.canModifyMessage(userId, messageId)) {
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "forbidden"))
                return@put
            }
            val ok = chatHistoryService.editMessage(messageId, userId, elements)
            if (ok) call.respond(mapOf("status" to "edited")) else call.respond(HttpStatusCode.NotFound, mapOf("error" to "not_found_or_not_owner"))
        }
    }

    private fun Routing.otherDisplayName() {
        put("/api/rooms/{roomId}/members/{targetUserId}/display_name") {
            val operatorId = requireAuth(call)
            val roomId = call.parameters["roomId"] ?: ""
            val targetUserId = call.parameters["targetUserId"] ?: ""
            val body = call.receive<Map<String, String>>()
            val displayName = body["display_name"] ?: ""
            if (displayName.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing display_name"))
                return@put
            }
            if (operatorId == targetUserId) {
                // 改自己走已有 API
                val ok = roomManager.setDisplayName(roomId, operatorId, displayName)
                call.respondText(apiJson.encodeToString(DisplayNameResponse(success = ok, displayName = displayName)), ContentType.Application.Json)
                return@put
            }
            if (!permissionService.canModifyOtherDisplayName(operatorId, roomId)) {
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "forbidden"))
                return@put
            }
            val ok = roomManager.setOtherDisplayName(roomId, targetUserId, displayName, operatorId)
            if (ok) {
                call.respondText(apiJson.encodeToString(DisplayNameResponse(success = true, displayName = displayName)), ContentType.Application.Json)
            } else {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "not_member"))
            }
        }
    }

    private fun Routing.getConfig() { get("/api/config") { requireAuth(call); call.respond(serverConfigManager.getAll()) } }

    private fun Routing.setConfig() {
        patch("/api/config/{key}") {
            val userId = requireAuth(call)
            if (!isOwner(userId)) { call.respond(HttpStatusCode.Forbidden, mapOf("error" to "owner_only")); return@patch }
            val key = call.parameters["key"] ?: ""
            val body = call.receive<Map<String, String>>()
            val value = body["value"] ?: ""
            serverConfigManager.set(key, value)
            call.respond(mapOf("status" to "updated"))
        }
    }

    private fun Routing.listPublicAdmins() {
        get("/api/public_admins") {
            requireAuth(call)
            val admins = authService.listPublicAdmins()
            call.respond(admins.map { mapOf("user_id" to it.userId, "username" to it.username) })
        }
    }

    private fun Routing.appointPublicAdmin() {
        post("/api/public_admins") {
            val ownerId = requireAuth(call)
            val body = call.receive<Map<String, String>>()
            val targetUserId = body["user_id"] ?: ""
            when (val r = authService.appointPublicAdmin(ownerId, targetUserId)) {
                is PublicAdminResult.Success -> call.respond(mapOf("status" to "appointed", "user_id" to r.userId))
                is PublicAdminResult.NotOwner -> call.respond(HttpStatusCode.Forbidden, mapOf("error" to "owner_only"))
                is PublicAdminResult.UserNotFound -> call.respond(HttpStatusCode.NotFound, mapOf("error" to "user_not_found"))
            }
        }
    }

    private fun Routing.removePublicAdmin() {
        delete("/api/public_admins/{userId}") {
            val ownerId = requireAuth(call)
            val targetUserId = call.parameters["userId"] ?: ""
            when (val r = authService.removePublicAdmin(ownerId, targetUserId)) {
                is PublicAdminResult.Success -> call.respond(mapOf("status" to "removed"))
                is PublicAdminResult.NotOwner -> call.respond(HttpStatusCode.Forbidden, mapOf("error" to "owner_only"))
                is PublicAdminResult.UserNotFound -> call.respond(HttpStatusCode.NotFound, mapOf("error" to "user_not_found"))
            }
        }
    }

    private fun Routing.avatarUpload() {
        put("/api/auth/avatar") {
            val userId = requireAuth(call)
            val mode = LandropProperties.getStorageMode()
            val baseDir = Path.of(LandropProperties.getFileBaseDir())
            val destDir = if (mode == "cloud") Path.of(LandropProperties.getStorageCloudCacheDir(), "avatar")
                          else baseDir.resolve(userId).resolve("avatar")
            File(destDir.toString()).mkdirs()
            val destFile = File(destDir.resolve("${userId}.jpg").toString())

            var saved = false
            kotlinx.coroutines.runBlocking {
                val multipart = call.receiveMultipart()
                multipart.forEachPart { part ->
                    if (part is PartData.FileItem) {
                        kotlinx.coroutines.runBlocking { part.provider() }
                            .toInputStream().use { src ->
                                destFile.outputStream().use { dst -> src.copyTo(dst) }
                            }
                        saved = true
                    }
                    (part as? PartData)?.dispose?.invoke()
                }
            }

            if (saved) {
                val storedPath = destFile.absolutePath
                if (mode == "cloud") {
                    log.warn("Avatar saved to cloud cache: {} — awaiting cloud uploader (TODO)", storedPath)
                } else {
                    log.info("Avatar uploaded: userId={}, path={}", userId, storedPath)
                }
                // 写入 DB
                when (authService.updateAvatarUrl(userId, storedPath)) {
                    is UpdateAvatarUrlResult.Success -> {}
                    is UpdateAvatarUrlResult.UserNotFound -> {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "user_not_found"))
                        return@put
                    }
                }
                // 返回 API URL，不泄露服务端路径
                val apiUrl = FileResolver.resolveAvatarUrl(storedPath, userId)
                call.respondText(apiJson.encodeToString(AvatarUploadResponse(success = true, avatarUrl = apiUrl)), ContentType.Application.Json)
            } else {
                call.respondText(apiJson.encodeToString(ErrorResponse(error = "no_file", message = "No file in request")), ContentType.Application.Json, HttpStatusCode.BadRequest)
            }
        }
    }

    private fun Routing.chatImageUpload() {
        put("/api/rooms/{roomId}/images") {
            val userId = requireAuth(call)
            val roomId = call.parameters["roomId"] ?: ""
            val mode = LandropProperties.getStorageMode()
            val baseDir = Path.of(LandropProperties.getFileBaseDir())
            val destDir = if (mode == "cloud") Path.of(LandropProperties.getStorageCloudCacheDir(), "img", roomId)
                          else baseDir.resolve(roomId).resolve("chatimg")
            File(destDir.toString()).mkdirs()
            val fileName = "${java.util.UUID.randomUUID()}.jpg"
            val destFile = File(destDir.resolve(fileName).toString())

            var saved = false
            kotlinx.coroutines.runBlocking {
                val multipart = call.receiveMultipart()
                multipart.forEachPart { part ->
                    if (part is PartData.FileItem) {
                        kotlinx.coroutines.runBlocking { part.provider() }
                            .toInputStream().use { src ->
                                destFile.outputStream().use { dst -> src.copyTo(dst) }
                            }
                        saved = true
                    }
                    (part as? PartData)?.dispose?.invoke()
                }
            }

            if (saved) {
                // 计算 SHA-256
                val checksum = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(destFile.readBytes()).joinToString("") { "%02x".format(it) }

                if (mode == "cloud") {
                    log.warn("Chat image saved to cloud cache: {} — awaiting cloud uploader (TODO)", destFile.absolutePath)
                } else {
                    log.info("Chat image uploaded: roomId={}, userId={}, fileName={}, checksum={}", roomId, userId, fileName, checksum)
                }
                // 写入 room_files 表获取 file_id
                val storedPath = destFile.absolutePath
                val fileId = roomManager.addRoomFile(roomId, fileName, destFile.length(), "image/jpeg", storedPath, checksum)
                // 返回 file_id，客户端自行构造下载 URL: /api/getfiles/{roomId}/{fileId}
                call.respondText(apiJson.encodeToString(
                    AvatarUploadResponse(success = true, avatarUrl = fileId)
                ), ContentType.Application.Json)
            } else {
                call.respondText(apiJson.encodeToString(ErrorResponse(error = "no_file", message = "No image in request")), ContentType.Application.Json, HttpStatusCode.BadRequest)
            }
        }
    }

        // TODO: 断点上传 — 当前不支持分块上传/断点续传，需后续引入技术栈后实现（如 tus 协议或自定义 chunk 机制）
        // TODO: 图片消息上传 — 当前直接存储文件，将来应改为上传图床、仅存 URL（与头像逻辑一致）
    private fun Routing.fileUpload() {
        post("/api/files/upload") {
            val userId = requireAuth(call)
            val uploadHandler = FileUploadHandler(config, bridge, fileChannelOps, jsonCodec, storageAllocator, roomManager)
            val result = uploadHandler.handle(userId, call)
            call.respond(result.first, result.second)
        }
    }

    /**
     * 秒传检测 — 按 SHA-256 在 room_files 表中查找已有文件，找到则秒传。
     * POST /api/files/check
     * Body: { "sha256": "...", "room_id": "..." }
     */
    private fun Routing.fileCheck() {
        post("/api/files/check") {
            requireAuth(call)
            val body = call.receive<Map<String, String>>()
            val sha256 = body["sha256"] ?: ""
            val roomId = body["room_id"] ?: ""
            if (sha256.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing_sha256"))
                return@post
            }
            val rf = roomManager.findRoomFileByChecksum(sha256)
            if (rf != null) {
                // 秒传：在目标房间新增一行
                val newFileId = if (roomId.isNotBlank()) roomManager.copyRoomFileToRoom(rf.fileId, roomId) else rf.fileId
                call.respond(mapOf("status" to "instant", "file_id" to (newFileId ?: rf.fileId)))
                return@post
            }
            call.respond(mapOf("status" to "upload_required"))
        }
    }

    /**
     * 秒传头尾验证（大文件 ≥10MB）。客户端提供头/尾 1MB SHA-256，服务端验证。
     * POST /api/files/check/verify
     * Body: { "file_id": "...", "head_sha256": "...", "tail_sha256": "...", "room_id": "..." }
     */
    private fun Routing.fileCheckVerify() {
        post("/api/files/check/verify") {
            requireAuth(call)
            val body = call.receive<Map<String, String>>()
            val fileId = body["file_id"] ?: ""
            val headSha256 = body["head_sha256"] ?: ""
            val tailSha256 = body["tail_sha256"] ?: ""
            val roomId = body["room_id"] ?: ""

            val rf = roomManager.getAnyRoomFileByFileId(fileId)
            if (rf == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "file_not_found"))
                return@post
            }
            val f = File(rf.storagePath)
            if (!f.exists()) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "file_not_found_on_disk"))
                return@post
            }
            if (f.length() < 10 * 1024 * 1024) {
                // 小文件不需要头尾验证
                val newFileId = if (roomId.isNotBlank()) roomManager.copyRoomFileToRoom(fileId, roomId) else fileId
                call.respond(mapOf("status" to "instant", "file_id" to (newFileId ?: fileId)))
                return@post
            }

            // 服务端计算头/尾 SHA-256
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val headSize = 1024 * 1024
            val tailSize = 1024 * 1024
            val buf = ByteArray(headSize)
            f.inputStream().use { src ->
                var read = src.read(buf)
                if (read > 0) md.update(buf, 0, read)
                val serverHead = md.digest().joinToString("") { "%02x".format(it) }
                if (!serverHead.equals(headSha256, ignoreCase = true)) {
                    call.respond(mapOf("status" to "mismatch", "field" to "head"))
                    return@post
                }
                md.reset()
                if (f.length() > tailSize) src.skip(f.length() - tailSize)
                val tailBuf = ByteArray(tailSize)
                var total = 0
                while (total < tailSize) {
                    read = src.read(tailBuf, total, tailSize - total)
                    if (read == -1) break
                    total += read
                }
                if (total > 0) md.update(tailBuf, 0, total)
                val serverTail = md.digest().joinToString("") { "%02x".format(it) }
                if (!serverTail.equals(tailSha256, ignoreCase = true)) {
                    call.respond(mapOf("status" to "mismatch", "field" to "tail"))
                    return@post
                }
            }
            val newFileId = if (roomId.isNotBlank()) roomManager.copyRoomFileToRoom(fileId, roomId) else fileId
            call.respond(mapOf("status" to "verified", "file_id" to (newFileId ?: fileId)))
        }
    }

    private fun Routing.roomFilesList() {
        get("/api/rooms/{roomId}/files") {
            val userId = requireAuth(call)
            val roomId = call.parameters["roomId"] ?: ""
            // 鉴权：必须是房间成员或管理员
            val memberIds = roomManager.getMemberIds(roomId)
            if (userId !in memberIds && !permissionService.canManageMember(userId, roomId)) {
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "forbidden"))
                return@get
            }
            val files = roomManager.listRoomFiles(roomId)
            call.respondText(apiJson.encodeToString(
                RoomFilesListResponse(
                    roomId = roomId,
                    count = files.size.toString(),
                    files = files.map { f ->
                        RoomFileItemResponse(
                            fileId = f.fileId,
                            fileName = f.fileName,
                            fileSize = f.fileSize.toString(),
                            mimeType = f.mimeType,
                            uploaderId = "",
                            uploadedAt = f.uploadedAt.toString()
                        )
                    }
                )
            ), ContentType.Application.Json)
        }
    }

    private fun Routing.roomFileDelete() {
        delete("/api/rooms/{roomId}/files/{fileId}") {
            val userId = requireAuth(call)
            val roomId = call.parameters["roomId"] ?: ""
            val fileId = call.parameters["fileId"] ?: ""
            // 查文件是否存在于此房间
            val file = roomManager.getRoomFileByFileIdAndRoomId(fileId, roomId)
            if (file == null) { call.respond(HttpStatusCode.NotFound, mapOf("error" to "not_found")); return@delete }
            // 鉴权：房间管理员即可删除
            if (!permissionService.canManageMember(userId, roomId)) {
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "forbidden"))
                return@delete
            }
            // 标记为过期清理（不立即删除，由 CleanupJobs 后续清理）
            val expiresAt = System.currentTimeMillis() + LandropProperties.getFileExpirationHours() * 3600 * 1000L
            val ok = roomManager.markRoomFileExpiring(fileId, roomId, expiresAt)
            if (ok) {
                call.respond(mapOf("status" to "marked_for_cleanup", "expires_at" to expiresAt.toString()))
            } else call.respond(HttpStatusCode.NotFound, mapOf("error" to "not_found"))
        }
    }

    /**
     * 静态文件暴露端点。
     *
     * 端点:
     *   GET /api/getfiles/avatar/{userId}   — 头像下载（需 JWT 认证，自动匹配扩展名）
     *   GET /api/getfiles/by-id/{fileId}    — 通过 fileId 下载（需 JWT + 房间权限）
     *   GET /api/getfiles/{roomId}/{path...} — 房间文件/图片下载（需 JWT + 房间成员/管理员权限）
     *
     * ★ 安全关键: 严格鉴权，不泄露其他用户的文件。
     */
    private fun Routing.getFiles() {
        // 头像下载：仅需 JWT 认证（头像为系统内公开信息，允许获取任意用户头像）
        get("/api/getfiles/avatar/{userId}") {
            requireAuth(call)
            val targetUserId = call.parameters["userId"] ?: ""
            val baseDir = Path.of(LandropProperties.getFileBaseDir())
            val avatarDir = baseDir.resolve(targetUserId).resolve("avatar")
            // 尝试本地文件
            val file = if (avatarDir.toFile().isDirectory) {
                avatarDir.toFile().listFiles()?.firstOrNull { it.isFile && it.name.startsWith("$targetUserId.") }
            } else null
            if (file != null) {
                call.respondFile(file)
                return@get
            }
            // 回退：查 DB avatar_url
            val avatarUrl = authService.getUserAvatarUrl(targetUserId)
                ?: FileResolver.DEFAULT_AVATAR_URL
            if (avatarUrl.startsWith("http://") || avatarUrl.startsWith("https://")) {
                call.respondRedirect(avatarUrl)
                return@get
            }
            if (avatarUrl == "NONE_URL" || avatarUrl.isBlank()) {
                call.respondRedirect(FileResolver.DEFAULT_AVATAR_URL)
                return@get
            }
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "avatar_not_found"))
        }

        // 统一文件下载：/api/getfiles/{roomId}/{id}
        // id 可以是 file_id（查 room_files）或 message_id（查 chat_messages）
        get("/api/getfiles/{roomId}/{id}") {
            val userId = requireAuth(call)
            val roomId = call.parameters["roomId"] ?: ""
            val id = call.parameters["id"] ?: ""

            // 鉴权：必须是房间成员 或 有管理权限
            val memberIds = roomManager.getMemberIds(roomId)
            val hasAccess = userId in memberIds || permissionService.canManageMember(userId, roomId)
            if (!hasAccess) {
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "forbidden"))
                return@get
            }

            // 1) 尝试 message_id 查找（优先 file_ref_id）
            val msg = chatHistoryService.getMessageById(id)
            if (msg != null && msg.roomId == roomId) {
                // 优先使用 file_ref_id 直接定位
                val refId = msg.fileRefId
                if (refId != null) {
                    val rf = roomManager.getRoomFileByFileIdAndRoomId(refId, roomId)
                    if (rf != null) {
                        val f = File(rf.storagePath)
                        if (f.exists() && f.isFile) {
                            call.respondFile(f)
                            return@get
                        }
                    }
                }
                // 回退 storage_path（兼容旧消息）
                if (msg.storagePath != null) {
                    val f = File(msg.storagePath)
                    if (f.exists() && f.isFile) {
                        call.respondFile(f)
                        return@get
                    }
                }
            }

            // 2) 尝试 file_id 查找（room_files 表）
            val rf = roomManager.getRoomFileByFileIdAndRoomId(id, roomId)
            if (rf != null) {
                val f = File(rf.storagePath)
                if (f.exists() && f.isFile) {
                    call.respondFile(f)
                    return@get
                }
            }

            call.respond(HttpStatusCode.NotFound, mapOf("error" to "file_not_found"))
        }
    }

    private fun Routing.fileDownload() {
        get("/api/files/{fileId}") {
            requireAuth(call)
            val fileId = call.parameters["fileId"] ?: ""
            val rf = roomManager.getAnyRoomFileByFileId(fileId)
            if (rf != null) {
                val f = File(rf.storagePath)
                if (f.exists() && f.isFile) {
                    // 支持断点续传（Range 请求由 Ktor respondFile 自动处理）
                    call.response.header("Accept-Ranges", "bytes")
                    call.response.header("Content-Disposition", "inline; filename=\"${rf.fileName}\"")
                    call.respondFile(f)
                    return@get
                }
            }
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "file_not_found"))
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 权限管理: promote/demote/deactivate/activate/ban
    // ═══════════════════════════════════════════════════════════

    private fun Routing.promoteMember() {
        put("/api/rooms/{roomId}/members/{userId}/promote") {
            val operatorId = requireAuth(call)
            val roomId = call.parameters["roomId"] ?: ""
            val targetUserId = call.parameters["userId"] ?: ""
            if (!permissionService.canManageAdmin(operatorId, roomId)) {
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "forbidden"))
                return@put
            }
            val ok = roomManager.promoteToAdmin(roomId, targetUserId)
            if (ok) call.respond(mapOf("status" to "promoted"))
            else call.respond(HttpStatusCode.BadRequest, mapOf("error" to "cannot_promote"))
        }
    }

    private fun Routing.demoteMember() {
        put("/api/rooms/{roomId}/members/{userId}/demote") {
            val operatorId = requireAuth(call)
            val roomId = call.parameters["roomId"] ?: ""
            val targetUserId = call.parameters["userId"] ?: ""
            if (!permissionService.canManageAdmin(operatorId, roomId)) {
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "forbidden"))
                return@put
            }
            val ok = roomManager.demoteFromAdmin(roomId, targetUserId)
            if (ok) call.respond(mapOf("status" to "demoted"))
            else call.respond(HttpStatusCode.BadRequest, mapOf("error" to "cannot_demote"))
        }
    }

    private fun Routing.deactivateUser() {
        put("/api/admin/users/{userId}/deactivate") {
            val operatorId = requireAuth(call)
            val targetUserId = call.parameters["userId"] ?: ""
            if (!permissionService.isOwner(operatorId) && !permissionService.isPublicAdmin(operatorId)) {
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "forbidden"))
                return@put
            }
            val ok = authService.deactivateUser(targetUserId)
            if (ok) call.respond(mapOf("status" to "deactivated"))
            else call.respond(HttpStatusCode.NotFound, mapOf("error" to "user_not_found"))
        }
    }

    private fun Routing.activateUser() {
        put("/api/admin/users/{userId}/activate") {
            val operatorId = requireAuth(call)
            val targetUserId = call.parameters["userId"] ?: ""
            if (!permissionService.isOwner(operatorId) && !permissionService.isPublicAdmin(operatorId)) {
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "forbidden"))
                return@put
            }
            val ok = authService.activateUser(targetUserId)
            if (ok) call.respond(mapOf("status" to "activated"))
            else call.respond(HttpStatusCode.NotFound, mapOf("error" to "user_not_found"))
        }
    }

    private fun Routing.banUser() {
        delete("/api/admin/users/{userId}") {
            val operatorId = requireAuth(call)
            val targetUserId = call.parameters["userId"] ?: ""
            if (!permissionService.isOwner(operatorId) && !permissionService.isPublicAdmin(operatorId)) {
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "forbidden"))
                return@delete
            }
            when (val r = authService.banUserFromPublic(targetUserId)) {
                is BanUserResult.Success ->
                    call.respond(mapOf("status" to "banned", "deleted_rooms" to r.deletedRooms.toString()))
                is BanUserResult.UserNotFound ->
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "user_not_found"))
            }
        }
    }

    companion object { private val log = LoggerFactory.getLogger(HttpApiRoutes::class.java) }
}

class AuthenticationException : RuntimeException("Authentication required")