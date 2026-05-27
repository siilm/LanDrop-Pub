package ink.siilm.gateway.ws

import ink.siilm.core.room.JoinResult
import ink.siilm.core.room.InviteResult
import ink.siilm.core.room.ApproveResult
import ink.siilm.core.auth.PermissionService
import ink.siilm.core.event.EventResult
import ink.siilm.core.message.ChatHistoryService
import ink.siilm.core.room.RoomJoinService
import ink.siilm.core.room.RoomManager
import ink.siilm.gateway.codec.JsonCodec
import ink.siilm.gateway.model.common.FileAttachment
import ink.siilm.gateway.model.common.MessageElement
import ink.siilm.gateway.model.ws.server.*
import ink.siilm.proto.InternalComm
import ink.siilm.proto.InternalComm.Envelope
import ink.siilm.shared.bridge.CoreBridge
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.util.*
import ink.siilm.gateway.model.common.RoomInfo as RoomInfoDto

private val wsJson = Json { explicitNulls = false; encodeDefaults = true }


/**
 * WebSocket 帧处理器。
 *
 * 负责客户端 JSON ↔ 内部 Protobuf Envelope 的双向转换。
 *
 * ## 入站 (handleIncoming)
 * 解析客户端 JSON → 根据 type 字段构造 [Envelope] → 通过 [CoreBridge.sendToCore] 投递。
 *
 * ## 出站 (handleOutgoing)
 * 根据 [Envelope.payload] oneof 分支构造出站 JSON → 通过 [WebSocketSessionManager.sendToUser] 发送。
 *
 * ### ★ 安全关键
 * 构造 MessageDelivery 的出站 JSON 时**不包含 storage_path**，防止客户端获取服务器文件路径。
 *
 * 注意: proto3 标量字段 (string/int64/bool) 不生成 has*() 方法，
 * 仅 message 类型字段和 oneof 字段有 has*()。
 */
class WebSocketFrameHandler(
    private val bridge: CoreBridge,
    private val sessionManager: WebSocketSessionManager,
    private val jsonCodec: JsonCodec,
    private val chatHistoryService: ChatHistoryService? = null,
    private val permissionService: PermissionService? = null,
    private val roomManager: RoomManager? = null,
    private val roomJoinService: RoomJoinService? = null,
    private val eventService: ink.siilm.core.event.EventService? = null
) {
    // ═══════════════════════════════════════════════════════════
    // 入站处理：客户端 JSON → Envelope → bridge
    // ═══════════════════════════════════════════════════════════

    /**
     * 处理来自客户端的入站 JSON 文本。
     */
    fun handleIncoming(userId: String, rawJson: String) {
        try {
            val obj = jsonCodec.parseToJsonObject(rawJson)
            val type = jsonCodec.getType(obj)

            if (type == "ping" || type == "pong"){
                log.debug("<<< [WS] parsed ping/pong type={} userId={}", type, userId)
            } else {
                log.info("<<< [WS] parsed type={} userId={}", type, userId)
            }

            when (type) {
                "chat_message"  -> handleChatMessage(userId, obj)
                "room_create"   -> handleRoomCommand(userId, InternalComm.RoomCommand.Action.CREATE, obj)
                "room_join"     -> handleWsJoinRoom(userId, obj)
                "room_leave"    -> handleRoomCommand(userId, InternalComm.RoomCommand.Action.LEAVE, obj)
                "room_list"     -> handleRoomCommand(userId, InternalComm.RoomCommand.Action.LIST, obj)
                "file_request"  -> handleFileInstruction(userId, obj)
                "ping"          -> handlePing(userId, obj)
                "pong"          -> handlePong(userId, obj)
                "ack"           -> handleAckIncoming(userId, obj)
                "chat_recall"   -> handleChatRecall(userId, obj)
                "chat_edit"     -> handleChatEdit(userId, obj)
                "join_request_ack" -> handleEventAck(userId, obj)
                "invite_ack"     -> handleEventAck(userId, obj)
                "event_ack"      -> handleEventAck(userId, obj)
                "room_dissolve" -> handleRoomCommand(userId, InternalComm.RoomCommand.Action.DESTROY, obj)
                "room_kick"     -> handleRoomKick(userId, obj)
                "room_mute"     -> handleRoomMute(userId, obj, true)
                "room_unmute"   -> handleRoomMute(userId, obj, false)
                "room_announce" -> handleRoomAnnounce(userId, obj)
                "room_invite"   -> handleRoomInvite(userId, obj)
                "room_invite_reply" -> handleRoomInviteReply(userId, obj)
                "event_confirm"    -> handleEventConfirm(userId, obj)
                "event_reject"     -> handleEventReject(userId, obj)
                "room_promote"     -> handleRoomPromote(userId, obj)
                "room_demote"      -> handleRoomDemote(userId, obj)
                "chat_delete"      -> handleChatDelete(userId, obj)
                else -> {
                    log.warn("Unknown message type '{}' from userId={}, raw={}", type, userId, rawJson)
                    sendErrorToUser(userId, null, 400, "Unknown message type: $type")
                }
            }
        } catch (e: Exception) {
            log.warn("Failed to parse incoming JSON from userId={}", userId, e)
            sendErrorToUser(userId, null, 400, "Invalid JSON format")
        }
    }

    /**
     * 处理踢出成员。
     */
    private fun handleRoomKick(operatorId: String, obj: JsonObject) {
        val roomId = jsonCodec.getString(obj, "room_id") ?: return
        val targetUserId = jsonCodec.getString(obj, "user_id") ?: return
        val perm = permissionService ?: return
        val rm = roomManager ?: return
        if (!perm.canManageMember(operatorId, roomId)) {
            sendErrorToUser(operatorId, null, 403, "forbidden")
            return
        }
        rm.kickMember(roomId, targetUserId)
        log.info("WS: {} kicked {} from {}", operatorId, targetUserId, roomId)
    }

    /**
     * 处理禁言/解除禁言。
     */
    private fun handleRoomMute(operatorId: String, obj: JsonObject, mute: Boolean) {
        val roomId = jsonCodec.getString(obj, "room_id") ?: return
        val targetUserId = jsonCodec.getString(obj, "user_id") ?: return
        val perm = permissionService ?: return
        val rm = roomManager ?: return
        if (!perm.canManageMember(operatorId, roomId)) {
            sendErrorToUser(operatorId, null, 403, "forbidden")
            return
        }
        if (mute) rm.muteMember(roomId, targetUserId) else rm.unmuteMember(roomId, targetUserId)
        log.info("WS: {} {} {} in {}", operatorId, if (mute) "muted" else "unmuted", targetUserId, roomId)
    }

    /**
     * 处理公告发布（WebSocket 广播给房间所有成员）。
     */
    private fun handleRoomAnnounce(userId: String, obj: JsonObject) {
        val roomId = jsonCodec.getString(obj, "room_id") ?: return
        val content = jsonCodec.getString(obj, "content") ?: return
        val perm = permissionService ?: return
        val rm = roomManager ?: return
        if (!perm.canBroadcast(userId, roomId)) {
            sendErrorToUser(userId, null, 403, "forbidden")
            return
        }
        val displayName = rm.getMemberDetails(roomId).find { it.userId == userId }?.displayName ?: userId
        val messageId = UUID.randomUUID().toString()
        val deliveryBuilder = InternalComm.MessageDelivery.newBuilder()
            .setFromUserId(userId)
            .setFromUsername(displayName)
            .setTextContent("[公告] $content")
            .setContentType(InternalComm.MessageDelivery.ContentType.TEXT)
        deliveryBuilder.roomId = roomId
        val envelope = Envelope.newBuilder()
            .setMessageId(messageId)
            .setTimestamp(System.currentTimeMillis())
            .setMessageDelivery(deliveryBuilder)
            .build()
        bridge.sendToCore(envelope)
        log.info("WS: Announce by {} in {}: {}", userId, roomId, content)
    }

    /**
     * 处理房间邀请。
     */
    private fun handleRoomInvite(inviterId: String, obj: JsonObject) {
        val roomId = jsonCodec.getString(obj, "room_id") ?: return
        val inviteeId = jsonCodec.getString(obj, "user_id") ?: return
        val rjs = roomJoinService ?: return
        when (val r = rjs.inviteToRoom(roomId, inviterId, inviteeId)) {
            is InviteResult.Success -> {
                // 通知受邀人（若在线）
                val inviteJson = wsJson.encodeToString(
                    ChatMessageOut(
                        type = "room_invite",
                        messageId = UUID.randomUUID().toString(),
                        from = inviterId,
                        displayName = inviterId,
                        elements = emptyList(),
                        content = "invited",
                        roomId = roomId,
                        timestamp = System.currentTimeMillis()
                    )
                )
                kotlinx.coroutines.runBlocking { sessionManager.sendToUser(inviteeId, inviteJson) }
                log.info("WS: {} invited {} to {}", inviterId, inviteeId, roomId)
            }
            else -> sendErrorToUser(inviterId, null, 400, r.toString().lowercase())
        }
    }

    /**
     * 处理邀请回复（受邀人接受/拒绝）。
     */
    private fun handleRoomInviteReply(inviteeId: String, obj: JsonObject) {
        val roomId = jsonCodec.getString(obj, "room_id") ?: return
        val inviteId = jsonCodec.getInt(obj, "invite_id") ?: return
        val accept = jsonCodec.getBoolean(obj, "accept") ?: true
        val rjs = roomJoinService ?: return
        if (accept) {
            when (val r = rjs.approveInvite(roomId, inviteId, inviteeId)) {
                is ApproveResult.ReferredToAdmin -> {
                    log.info("WS: {} accepted invite, referred to admin: roomId={}", inviteeId, roomId)
                    val notification = """{"type":"join_request","event_id":"${r.eventId}","room_id":"$roomId","applicant_id":"$inviteeId"}"""
                    kotlinx.coroutines.runBlocking { sessionManager.notifyRoomAdmins(roomId, notification) }
                }
                ApproveResult.Success -> log.info("WS: {} accepted invite {} to {}", inviteeId, inviteId, roomId)
                else -> sendErrorToUser(inviteeId, null, 404, "invite_not_found")
            }
        } else {
            when (rjs.rejectInvite(roomId, inviteId, inviteeId)) {
                ApproveResult.Success -> log.info("WS: {} rejected invite {} to {}", inviteeId, inviteId, roomId)
                else -> sendErrorToUser(inviteeId, null, 404, "invite_not_found")
            }
        }
    }

    /**
     * 处理客户端 Ack 确认。
     * 客户端收到服务端消息后回复 Ack，服务端将其路由回原始发送者。
     */
    private fun handleAckIncoming(userId: String, obj: JsonObject) {
        val messageId = jsonCodec.getString(obj, "message_id") ?: return
        val status = jsonCodec.getString(obj, "status") ?: "ok"
        val originalSender = sessionManager.getUserIdByMessageId(messageId)
        if (originalSender != null) {
            val ackJson = wsJson.encodeToString(AckOut(messageId = messageId, status = status))
            kotlinx.coroutines.runBlocking { sessionManager.sendToUser(originalSender, ackJson) }
            log.debug("Ack routed: refMessageId={}, from={}, to={}", messageId, userId, originalSender)
        } else {
            log.debug("Ack dropped (original sender not found): refMessageId={}", messageId)
        }
    }

    /**
     * 通用事件 Ack 处理（统一 join_request_ack / invite_ack / event_ack）。
     */
    private fun handleEventAck(userId: String, obj: JsonObject) {
        val eventId = jsonCodec.getString(obj, "event_id") ?: return
        val svc = eventService ?: return
        when (svc.confirmEvent(eventId, userId)) {
            is ink.siilm.core.event.EventResult.Confirmed -> {
                log.debug("event_ack confirmed: eventId={}, by={}", eventId, userId)
            }
            is ink.siilm.core.event.EventResult.Expired -> {
                sendErrorToUser(userId, null, 410, "event_expired")
            }
            is ink.siilm.core.event.EventResult.NotFound -> {
                sendErrorToUser(userId, null, 404, "event_not_found")
            }
            is ink.siilm.core.event.EventResult.AlreadyProcessed -> {
                log.debug("event_ack already processed: eventId={}", eventId)
            }
            else -> {}
        }
    }

    /**
     * 处理邀请 Ack（受邀人确认收到通知）。保留兼容，委托给 handleEventAck。
     */
    @Deprecated("Use event_ack instead")
    private fun handleInviteAck(userId: String, obj: JsonObject) = handleEventAck(userId, obj)

    /**
     * 处理加入申请 Ack（房间管理员确认收到通知）。保留兼容，委托给 handleEventAck。
     */
    @Deprecated("Use event_ack instead")
    private fun handleJoinRequestAck(userId: String, obj: JsonObject) = handleEventAck(userId, obj)

    /**
     * 处理消息撤回。
     */
    private fun handleChatRecall(userId: String, obj: JsonObject) {
        val messageId = jsonCodec.getString(obj, "message_id") ?: return
        val svc = chatHistoryService ?: return
        val perm = permissionService ?: return
        if (!perm.canModifyMessage(userId, messageId)) {
            sendErrorToUser(userId, null, 403, "forbidden")
            return
        }
        val ok = svc.recallMessage(messageId, userId)
        if (ok) {
            // 广播撤回通知给房间内其他成员
            val roomId = jsonCodec.getString(obj, "room_id")
            if (roomId != null) {
                val userIds = sessionManager.getUserIdsInRoom(roomId) - userId
                val notify = wsJson.encodeToString(RecallOut(messageId = messageId, roomId = roomId))
                userIds.forEach { kotlinx.coroutines.runBlocking { sessionManager.sendToUser(it, notify) } }
            }
            log.info("Message {} recalled by {} via WS", messageId, userId)
        }
    }

    /**
     * 处理消息编辑。
     */
    private fun handleChatEdit(userId: String, obj: JsonObject) {
        val messageId = jsonCodec.getString(obj, "message_id") ?: return
        val elements = jsonCodec.getArray(obj, "elements")?.toString() ?: return
        val svc = chatHistoryService ?: return
        val perm = permissionService ?: return
        if (!perm.canModifyMessage(userId, messageId)) {
            sendErrorToUser(userId, null, 403, "forbidden")
            return
        }
        val ok = svc.editMessage(messageId, userId, elements)
        if (ok) {
            // 广播编辑通知给房间内其他成员
            val roomId = jsonCodec.getString(obj, "room_id")
            if (roomId != null) {
                val userIds = sessionManager.getUserIdsInRoom(roomId) - userId
                val notify = wsJson.encodeToString(EditOut(messageId = messageId, elements = elements, roomId = roomId))
                userIds.forEach { kotlinx.coroutines.runBlocking { sessionManager.sendToUser(it, notify) } }
            }
            log.info("Message {} edited by {} via WS", messageId, userId)
        }
    }

    /**
     * 处理聊天消息。
     */
    private fun handleChatMessage(userId: String, obj: JsonObject) {
        val content = jsonCodec.getString(obj, "content") ?: ""
        val elements = jsonCodec.getArray(obj, "elements")
        val toUserId = jsonCodec.getString(obj, "to")
        val roomId = jsonCodec.getString(obj, "room_id")

        // 鉴权：发送者必须在目标房间内
        if (roomId != null && roomManager != null) {
            if (userId !in roomManager.getMemberIds(roomId)) {
                sendErrorToUser(userId, null, 403, "not_member_of_room")
                return
            }
        }

        // elements JSON 数组，无则从 content 降级构造
        // elements JSON 数组，无则从 content 降级构造（使用数据类序列化）
        val elementsJson = elements?.toString()
            ?: wsJson.encodeToString(listOf(MessageElement(type = "text", content = content)))
        /* DEPRECATED buildJsonArray:
        buildJsonArray { add(buildJsonObject { put("type", "text"); put("content", content) }) }.toString()
        */

        // 检查 elements 中是否包含图片类型
        var hasPicture = false
        var pictureFileId: String? = null
        if (elements != null) {
            for (el in elements) {
                val elObj = el as? JsonObject ?: continue
                val elType = elObj["type"]?.jsonPrimitive?.contentOrNull
                if (elType == "picture" || elType == "image" || elType == "file") {
                    hasPicture = true
                    pictureFileId = elObj["file_id"]?.jsonPrimitive?.contentOrNull
                }
            }
        }

        val contentType = if (hasPicture) InternalComm.MessageDelivery.ContentType.IMAGE
                         else InternalComm.MessageDelivery.ContentType.TEXT

        val deliveryBuilder = InternalComm.MessageDelivery.newBuilder()
            .setFromUserId(userId)
            .setTextContent(content)
            .setContentType(contentType)

        // 设置 elements JSON（原样传递）
        deliveryBuilder.elements = elementsJson

        // 设置 storage_path 和 file_ref_id（仅当消息含图片/文件时）
        if (hasPicture && roomId != null && pictureFileId != null) {
            val rf = roomManager?.getAnyRoomFileByFileId(pictureFileId)
            if (rf != null) {
                deliveryBuilder.storagePath = rf.storagePath
                deliveryBuilder.fileRefId = pictureFileId
            } else {
                log.warn("Room file not found for fileId={}, roomId={}", pictureFileId, roomId)
            }
        }

        if (roomId != null) deliveryBuilder.roomId = roomId
        else if (toUserId != null) deliveryBuilder.toUserId = toUserId

        val envelope = Envelope.newBuilder()
            .setMessageId(generateMessageId())
            .setTimestamp(System.currentTimeMillis())
            .setSessionId(sessionManager.getSessionId(userId) ?: "")
            .setMessageDelivery(deliveryBuilder)
            .build()

        bridge.sendToCore(envelope)
        sessionManager.trackMessageId(userId, envelope.messageId)

        // 向发送方回复 Ack 确认收到
        val ackJson = wsJson.encodeToString(AckOut(messageId = envelope.messageId, status = "ok"))
        kotlinx.coroutines.runBlocking { sessionManager.sendToUser(userId, ackJson) }
    }
    /**
     * WS room_join 处理：改为走 RoomJoinService.joinRoom 而非直接 core 命令。
     */
    private fun handleWsJoinRoom(userId: String, obj: JsonObject) {
        val roomId = jsonCodec.getString(obj, "room_id") ?: ""
        val rjs = roomJoinService ?: return
        val message = jsonCodec.getString(obj, "message")
        when (val result = rjs.joinRoom(roomId, userId, message)) {
            JoinResult.Joined -> {
                val resp = """{"type":"room_join_result","room_id":"$roomId","status":"joined"}"""
                kotlinx.coroutines.runBlocking { sessionManager.sendToUser(userId, resp) }
            }
            is JoinResult.Pending -> {
                val resp = """{"type":"room_join_result","room_id":"$roomId","status":"pending"}"""
                kotlinx.coroutines.runBlocking { sessionManager.sendToUser(userId, resp) }
                // 通知房间管理员
                val notification = """{"type":"join_request","event_id":"${result.eventId}","room_id":"$roomId","applicant_id":"$userId"}"""
                kotlinx.coroutines.runBlocking { sessionManager.notifyRoomAdmins(roomId, notification) }
            }
            JoinResult.AlreadyMember -> {
                sendErrorToUser(userId, null, 409, "already_member")
            }
            JoinResult.RoomNotFound -> {
                sendErrorToUser(userId, null, 404, "room_not_found")
            }
            JoinResult.RoomDissolved -> {
                sendErrorToUser(userId, null, 410, "room_dissolved")
            }
            JoinResult.RequestAlreadyPending -> {
                sendErrorToUser(userId, null, 409, "request_already_pending")
            }
        }
    }

    /**
     * 处理房间命令（create/join/leave/list）。
     */
    private fun handleRoomCommand(userId: String, action: InternalComm.RoomCommand.Action, obj: JsonObject) {
        val roomId = jsonCodec.getString(obj, "room_id") ?: ""
        val roomName = jsonCodec.getString(obj, "room_name") ?: jsonCodec.getString(obj, "name") ?: ""

        val command = InternalComm.RoomCommand.newBuilder()
            .setAction(action)
            .setUserId(userId)
            .setRoomId(roomId)
            .setRoomName(roomName)

        val envelope = Envelope.newBuilder()
            .setMessageId(generateMessageId())
            .setTimestamp(System.currentTimeMillis())
            .setSessionId(sessionManager.getSessionId(userId) ?: "")
            .setRoomCommand(command)
            .build()

        bridge.sendToCore(envelope)
        sessionManager.trackMessageId(userId, envelope.messageId)
    }

    /**
     * 处理文件传输请求。
     */
    private fun handleFileInstruction(userId: String, obj: JsonObject) {
        val fileId = jsonCodec.getString(obj, "file_id") ?: ""
        val fileName = jsonCodec.getString(obj, "file_name") ?: ""
        val fileSize = jsonCodec.getLong(obj, "file_size") ?: 0L
        val targetUserId = jsonCodec.getString(obj, "to_user_id")

        val instruction = InternalComm.FileInstruction.newBuilder()
            .setCommand(InternalComm.FileInstruction.Command.REQUEST_UPLOAD_SLOT)
            .setUserId(userId)
            .setFileId(fileId)
            .setMaxSize(fileSize)
        if (targetUserId != null) {
            instruction.targetUserId = targetUserId
        }

        val envelope = Envelope.newBuilder()
            .setMessageId(generateMessageId())
            .setTimestamp(System.currentTimeMillis())
            .setSessionId(sessionManager.getSessionId(userId) ?: "")
            .setFileInstruction(instruction)
            .build()

        bridge.sendToCore(envelope)
        sessionManager.trackMessageId(userId, envelope.messageId)
    }

    /**
     * 处理 PING 心跳。
     */
    private fun handlePing(userId: String, obj: JsonObject) {
        val sessionEvent = InternalComm.SessionEvent.newBuilder()
            .setUserId(userId)
            .setType(InternalComm.SessionEvent.Type.PING)

        val envelope = Envelope.newBuilder()
            .setMessageId(generateMessageId())
            .setTimestamp(System.currentTimeMillis())
            .setSessionId(sessionManager.getSessionId(userId) ?: "")
            .setSessionEvent(sessionEvent)
            .build()

        bridge.sendToCore(envelope)
        sessionManager.trackMessageId(userId, envelope.messageId)

        // 立即回复 PONG，不等待 core
        val pongJson = wsJson.encodeToString(PongOut(timestamp = System.currentTimeMillis()))
        kotlinx.coroutines.runBlocking {
            sessionManager.sendToUser(userId, pongJson)
        }
    }

    /**
     * 处理 PONG 响应。
     */
    private fun handlePong(userId: String, obj: JsonObject) {
        sessionManager.recordPong(userId)

        val sessionEvent = InternalComm.SessionEvent.newBuilder()
            .setUserId(userId)
            .setType(InternalComm.SessionEvent.Type.PONG)

        val envelope = Envelope.newBuilder()
            .setMessageId(generateMessageId())
            .setTimestamp(System.currentTimeMillis())
            .setSessionId(sessionManager.getSessionId(userId) ?: "")
            .setSessionEvent(sessionEvent)
            .build()

        bridge.sendToCore(envelope)
        sessionManager.trackMessageId(userId, envelope.messageId)
    }

    /**
     * 向用户发送错误消息。
     */
    private fun sendErrorToUser(userId: String, refMessageId: String?, errorCode: Int, message: String) {
        val errorJson = wsJson.encodeToString(ErrorOut(refMessageId = refMessageId, errorCode = errorCode, errorMessage = message))
        kotlinx.coroutines.runBlocking {
            sessionManager.sendToUser(userId, errorJson)
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 出站处理：Envelope → JSON → WebSocket
    // ═══════════════════════════════════════════════════════════

    /**
     * 处理来自 core 的出站 [Envelope]。
     * 根据 payload 类型分支，构造 JSON 并通过 [WebSocketSessionManager.sendToUser] 发送给目标客户端。
     */
    suspend fun handleOutgoing(envelope: Envelope) {
        try {
            val payloadType = when {
                envelope.hasMessageDelivery() -> "message"
                envelope.hasSessionEvent()    -> "session"
                envelope.hasFileInstruction() -> "file"
                envelope.hasRoomCommand()     -> "room"
                envelope.hasAck()             -> "ack"
                envelope.hasError()           -> "error"
                else -> "unknown"
            }
            log.debug(">>> [WS] outbound type={} messageId={}", payloadType, envelope.messageId)

            when {
                envelope.hasMessageDelivery() -> handleOutgoingMessageDelivery(envelope)
                envelope.hasSessionEvent()    -> handleOutgoingSessionEvent(envelope)
                envelope.hasFileInstruction() -> handleOutgoingFileInstruction(envelope)
                envelope.hasRoomCommand()     -> handleOutgoingRoomCommand(envelope)
                envelope.hasAck()             -> handleOutgoingAck(envelope)
                envelope.hasError()           -> handleOutgoingError(envelope)
                else -> log.warn("Unknown outbound envelope payload: messageId={}", envelope.messageId)
            }
        } catch (e: Exception) {
            log.error("Failed to handle outgoing envelope messageId={}", envelope.messageId, e)
        }
    }

    /**
     * 出站 MessageDelivery → chat_message JSON。
     *
     * ★ 安全关键：不包含 storage_path。
     * 注意: proto3 标量字段无 has*(), 直接使用默认空字符串即可。
     */
    private suspend fun handleOutgoingMessageDelivery(envelope: Envelope) {
        val delivery = envelope.messageDelivery
        val elements = mutableListOf<MessageElement>()
        if (delivery.textContent.isNotEmpty()) {
            elements.add(MessageElement(type = "text", content = delivery.textContent))
        }
        if (delivery.hasFileRef()) {
            val fr = delivery.fileRef
            elements.add(MessageElement(
                type = if (delivery.contentType == InternalComm.MessageDelivery.ContentType.IMAGE) "image" else "file",
                fileId = fr.fileId, fileName = fr.fileName, fileSize = fr.fileSize,
                mimeType = fr.mimeType.takeIf { it.isNotEmpty() }
            ))
        }
        val file = if (delivery.hasFileRef()) {
            val fr = delivery.fileRef
            val downloadUrl = if (delivery.hasRoomId()) "/api/getfiles/${delivery.roomId}/${fr.fileId}"
                              else "/api/getfiles/by-id/${fr.fileId}"  // 私聊回退
            FileAttachment(fr.fileId, fr.fileName, fr.fileSize, fr.mimeType.takeIf { it.isNotEmpty() }, fr.checksumSha256.takeIf { it.isNotEmpty() }, downloadUrl)
        } else null

        val msg = ChatMessageOut(
            messageId = envelope.messageId,
            from = delivery.fromUserId,
            displayName = delivery.fromUsername.ifEmpty { delivery.fromUserId },
            elements = elements,
            content = delivery.textContent.takeIf { it.isNotEmpty() },
            file = file,
            roomId = delivery.roomId.takeIf { delivery.hasRoomId() },
            to = delivery.toUserId.takeIf { delivery.hasToUserId() },
            timestamp = envelope.timestamp
        )
        val json = wsJson.encodeToString(msg)

        if (delivery.hasToUserId()) {
            sessionManager.sendToUser(delivery.toUserId, json)
        } else if (delivery.hasRoomId()) {
            val userIds = sessionManager.getUserIdsInRoom(delivery.roomId)
            userIds.forEach { uid -> sessionManager.sendToUser(uid, json) }
        }
    }

    /**
     * 出站 SessionEvent → 对应类型 JSON。
     */
    private suspend fun handleOutgoingSessionEvent(envelope: Envelope) {
        val event = envelope.sessionEvent
        val userId = event.userId

        when (event.type) {
            InternalComm.SessionEvent.Type.PONG -> {
                val json = wsJson.encodeToString(PongOut(timestamp = envelope.timestamp))
                sessionManager.sendToUser(userId, json)
            }
            InternalComm.SessionEvent.Type.KICKED -> {
                val json = wsJson.encodeToString(KickedOut(reason = event.reason.takeIf { it.isNotEmpty() }))
                sessionManager.sendToUser(userId, json)
            }
            else -> {
                // CONNECTED, DISCONNECTED 不直接发送给客户端
                log.debug("Outbound session event type={} for userId={}, not forwarded to client", event.type, userId)
            }
        }
    }

    /**
     * 出站 FileInstruction → file_instruction JSON。
     */
    private suspend fun handleOutgoingFileInstruction(envelope: Envelope) {
        val instruction = envelope.fileInstruction
        val userId = instruction.userId

        val commandStr = when (instruction.command) {
            InternalComm.FileInstruction.Command.UPLOAD_SLOT_GRANTED -> "upload_slot_granted"
            InternalComm.FileInstruction.Command.UPLOAD_REJECTED    -> "upload_rejected"
            InternalComm.FileInstruction.Command.DOWNLOAD_INFO_READY -> "download_info_ready"
            else -> instruction.command.name.lowercase()
        }

        val msg = FileInstructionOut(
            command = commandStr,
            fileId = instruction.fileId,
            storagePath = instruction.storagePath.takeIf { it.isNotEmpty() },
            maxSize = instruction.maxSize.takeIf { it != 0L },
            errorReason = instruction.errorReason.takeIf { it.isNotEmpty() }
        )
        sessionManager.sendToUser(userId, wsJson.encodeToString(msg))
    }

    /**
     * 出站 RoomCommand → 房间事件 JSON。
     */
    private suspend fun handleOutgoingRoomCommand(envelope: Envelope) {
        val command = envelope.roomCommand
        val userId = command.userId

        val eventType = when (command.action) {
            InternalComm.RoomCommand.Action.CREATE  -> "room_created"
            InternalComm.RoomCommand.Action.JOIN    -> "room_joined"
            InternalComm.RoomCommand.Action.LEAVE   -> "room_left"
            InternalComm.RoomCommand.Action.DESTROY -> "room_destroyed"
            InternalComm.RoomCommand.Action.LIST    -> "room_list"
            else -> "room_event"
        }

        val rooms = if (command.roomsCount > 0) {
            command.roomsList.map { RoomInfoDto(it.roomId, it.roomName, it.memberCount.toString(), it.hasPassword) }
        } else null

        val event = RoomEventOut(
            type = eventType,
            userId = userId,
            roomId = command.roomId.takeIf { it.isNotEmpty() },
            roomName = command.roomName.takeIf { it.isNotEmpty() },
            rooms = rooms
        )
        val json = wsJson.encodeToString(event)
        sessionManager.sendToUser(userId, json)
    }

    /**
     * 出站 Ack → ack JSON。
     *
     * 通过 refMessageId 查找原始发送者的 userId，若找到则向其发送 ACK。
     * 若未找到映射（用户已断连或消息已过期），降级为记录日志。
     */
    private suspend fun handleOutgoingAck(envelope: Envelope) {
        val ack = envelope.ack
        val status = if (ack.status == InternalComm.Ack.Status.OK) "ok" else "error"

        val json = wsJson.encodeToString(AckOut(messageId = ack.refMessageId, status = status))

        val targetUserId = sessionManager.getUserIdByMessageId(ack.refMessageId)
        if (targetUserId != null) {
            sessionManager.sendToUser(targetUserId, json)
        } else {
            log.debug("Outbound ACK dropped (user not found): refMessageId={}, status={}", ack.refMessageId, status)
        }
    }

    /**
     * 出站 ErrorInfo → error JSON。
     *
     * 通过 refMessageId 查找原始发送者的 userId，若找到则向其发送错误。
     * 若未找到映射，降级为记录日志。
     */
    private suspend fun handleOutgoingError(envelope: Envelope) {
        val error = envelope.error
        val refId = error.refMessageId.takeIf { it.isNotEmpty() }

        val errorTypeStr = when (error.errorType) {
            InternalComm.ErrorInfo.ErrorType.INVALID_MESSAGE -> "invalid_message"
            InternalComm.ErrorInfo.ErrorType.ROOM_NOT_FOUND -> "room_not_found"
            InternalComm.ErrorInfo.ErrorType.USER_OFFLINE -> "user_offline"
            InternalComm.ErrorInfo.ErrorType.PERMISSION_DENIED -> "permission_denied"
            InternalComm.ErrorInfo.ErrorType.FILE_TOO_LARGE -> "file_too_large"
            InternalComm.ErrorInfo.ErrorType.INTERNAL_ERROR -> "internal_error"
            else -> null
        }

        val json = wsJson.encodeToString(ErrorOut(
            refMessageId = refId,
            errorCode = error.errorCode,
            errorMessage = error.errorMessage,
            errorType = errorTypeStr
        ))

        val targetUserId = refId?.let { sessionManager.getUserIdByMessageId(it) }
        if (targetUserId != null) {
            sessionManager.sendToUser(targetUserId, json)
        } else {
            log.warn("Outbound error dropped (user not found): refMessageId={}, code={}, message={}",
                refId ?: "n/a",
                error.errorCode,
                error.errorMessage
            )
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 消息删除（永久）
    // ═══════════════════════════════════════════════════════════

    private fun handleChatDelete(userId: String, obj: JsonObject) {
        val messageId = jsonCodec.getString(obj, "message_id") ?: ""
        val roomId = jsonCodec.getString(obj, "room_id")
        val chs = chatHistoryService ?: return sendErrorToUser(userId, null, 500, "ChatHistoryService unavailable")
        if (!chs.deleteMessage(messageId, userId)) {
            sendErrorToUser(userId, null, 404, "Message not found or not owner")
            return
        }
        log.info("Message deleted: messageId={} by {}", messageId, userId)
        // 通知房间其他成员
        if (roomId != null) {
            val userIds = sessionManager.getUserIdsInRoom(roomId) - userId
            val notify = buildJsonObject {
                put("type", "chat_deleted")
                put("message_id", messageId)
                put("room_id", roomId)
                put("deleted_by", userId)
            }.toString()
            userIds.forEach { kotlinx.coroutines.runBlocking { sessionManager.sendToUser(it, notify) } }
        }
        // 向操作者发送确认
        val ack = buildJsonObject {
            put("type", "ack")
            put("ref_message_id", messageId)
            put("status", "deleted")
        }.toString()
        kotlinx.coroutines.runBlocking { sessionManager.sendToUser(userId, ack) }
    }

    // ═══════════════════════════════════════════════════════════
    // 房间管理：晋升/降级
    // ═══════════════════════════════════════════════════════════

    private fun handleRoomPromote(operatorId: String, obj: JsonObject) {
        val roomId = jsonCodec.getString(obj, "room_id") ?: ""
        val targetUserId = jsonCodec.getString(obj, "target_user_id") ?: ""
        val rm = roomManager ?: return sendErrorToUser(operatorId, null, 500, "RoomManager unavailable")
        if (!rm.promoteToAdmin(roomId, targetUserId)) {
            sendErrorToUser(operatorId, null, 403, "Permission denied or member not found")
            return
        }
        log.info("Promoted: roomId={}, operator={}, target={}", roomId, operatorId, targetUserId)
        // 通知被晋升的用户
        val notify = buildJsonObject {
            put("type", "room_promoted")
            put("room_id", roomId)
            put("promoted_by", operatorId)
        }.toString()
        kotlinx.coroutines.runBlocking { sessionManager.sendToUser(targetUserId, notify) }
    }

    private fun handleRoomDemote(operatorId: String, obj: JsonObject) {
        val roomId = jsonCodec.getString(obj, "room_id") ?: ""
        val targetUserId = jsonCodec.getString(obj, "target_user_id") ?: ""
        val rm = roomManager ?: return sendErrorToUser(operatorId, null, 500, "RoomManager unavailable")
        if (!rm.demoteFromAdmin(roomId, targetUserId)) {
            sendErrorToUser(operatorId, null, 403, "Permission denied or member not found")
            return
        }
        log.info("Demoted: roomId={}, operator={}, target={}", roomId, operatorId, targetUserId)
        val notify = buildJsonObject {
            put("type", "room_demoted")
            put("room_id", roomId)
            put("demoted_by", operatorId)
        }.toString()
        kotlinx.coroutines.runBlocking { sessionManager.sendToUser(targetUserId, notify) }
    }

    // ═══════════════════════════════════════════════════════════
    // Event 事件处理
    // ═══════════════════════════════════════════════════════════

    private fun handleEventConfirm(userId: String, obj: JsonObject) {
        val eventId = jsonCodec.getString(obj, "event_id") ?: ""
        val es = eventService ?: return sendErrorToUser(userId, null, 500, "EventService unavailable")
        when (es.confirmEvent(eventId, userId)) {
            is EventResult.Confirmed -> log.info("Event confirmed: eventId={} by {}", eventId, userId)
            is EventResult.NotFound -> sendErrorToUser(userId, null, 404, "Event not found")
            is EventResult.AlreadyProcessed -> sendErrorToUser(userId, null, 409, "Event already processed")
            is EventResult.Rejected -> sendErrorToUser(userId, null, 409, "Event was rejected")
            is EventResult.Expired -> sendErrorToUser(userId, null, 410, "Event expired")
        }
    }

    private fun handleEventReject(userId: String, obj: JsonObject) {
        val eventId = jsonCodec.getString(obj, "event_id") ?: ""
        val es = eventService ?: return sendErrorToUser(userId, null, 500, "EventService unavailable")
        when (es.rejectEvent(eventId, userId)) {
            is EventResult.Confirmed -> log.info("Event rejected (was confirmed): eventId={} by {}", eventId, userId)
            is EventResult.Rejected -> log.info("Event rejected: eventId={} by {}", eventId, userId)
            is EventResult.NotFound -> sendErrorToUser(userId, null, 404, "Event not found")
            is EventResult.AlreadyProcessed -> sendErrorToUser(userId, null, 409, "Event already processed")
            is EventResult.Expired -> sendErrorToUser(userId, null, 410, "Event expired")
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 辅助方法
    // ═══════════════════════════════════════════════════════════

    companion object {
        private val log = LoggerFactory.getLogger(WebSocketFrameHandler::class.java)
        private fun generateMessageId() = UUID.randomUUID().toString()
    }
}