package ink.siilm.gateway.cli

import ink.siilm.core.auth.RegisterResult
import ink.siilm.core.auth.BanUserResult
import ink.siilm.core.auth.AuthService
import ink.siilm.core.persistence.RoomTable
import ink.siilm.core.persistence.UserTable
import ink.siilm.core.room.RoomManager
import ink.siilm.gateway.ws.WebSocketSessionManager
import ink.siilm.shared.config.LandropProperties
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.Logger
import java.io.File

/**
 * CLI 指令的具体实现。
 * 所有方法返回 [String] 用于输出到终端。
 */
class CliHandlers(
    private val authService: AuthService,
    private val roomManager: RoomManager,
    private val sessionManager: WebSocketSessionManager
) {
    private val log: Logger = org.slf4j.LoggerFactory.getLogger(CliHandlers::class.java)
    private val startTime = System.currentTimeMillis()

    // ═══════════════════════════════════════════════════════════

    fun stat(): String {
        val runtime = Runtime.getRuntime()
        val usedMem = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val maxMem = runtime.maxMemory() / (1024 * 1024)
        val diskBytes = File(LandropProperties.getFileBaseDir()).walkTopDown().sumOf { it.length() }
        val diskGB = String.format("%.2f", diskBytes / (1024.0 * 1024 * 1024))
        val online = sessionManager.activeCount()
        val activeRooms = transaction {
            RoomTable.selectAll().where { RoomTable.status eq RoomManager.STATUS_ACTIVE }.count()
        }
        val uptimeSec = (System.currentTimeMillis() - startTime) / 1000
        val d = uptimeSec / 86400; val h = (uptimeSec % 86400) / 3600; val m = (uptimeSec % 3600) / 60
        return buildString {
            appendLine("=== LanDrop Server Status ===")
            appendLine("JVM Memory:    ${usedMem}MB used / ${maxMem}MB max")
            appendLine("Disk (files):  $diskGB GB  (${LandropProperties.getFileBaseDir()})")
            appendLine("Online Users:  $online")
            appendLine("Active Rooms:  $activeRooms")
            appendLine("Uptime:        ${d}d ${h}h ${m}m")
        }
    }

    // ═══════════════════════════════════════════════════════════

    fun userAdd(username: String, userId: String? = null): String {
        return when (val r = authService.registerUser(username, userId)) {
            is RegisterResult.Success -> {
                // 将密钥写入文件系统（与 HTTP API 保持一致的存储位置）
                val secretsDir = java.io.File(LandropProperties.getSecretsDir(), r.userId)
                secretsDir.mkdirs()
                java.io.File(secretsDir, "${r.userId}.key").writeText(r.privateKeyBase64)
                java.io.File(secretsDir, "${r.userId}.pub").writeText(r.publicKeyBase64)
                log.info("CLI: user {} created (userId={}, role={})", username, r.userId, r.globalRole)
                "OK: user $username created (userId=${r.userId}, role=${r.globalRole}, keyDir=${secretsDir.absolutePath})"
            }
            is RegisterResult.InvalidUserId -> "ERROR: invalid user_id format (12 alphanumeric)"
            is RegisterResult.UsernameTooLong -> "ERROR: username max 25 characters"
            is RegisterResult.UserIdAlreadyExists -> "ERROR: user_id collision, retry"
        }
    }

    fun userDel(userId: String): String {
        val exists = transaction { UserTable.selectAll().where { UserTable.userId eq userId }.count() }
        if (exists == 0L) return "ERROR: user not found"
        val result = authService.banUserFromPublic(userId)
        val detail = when (result) {
            is BanUserResult.Success -> "OK: user $userId deleted (${result.deletedRooms} rooms cascade)"
            is BanUserResult.UserNotFound -> "ERROR: user not found"
        }
        log.info("CLI: userdel {} -> {}", userId, detail)
        return detail
    }

    fun userMod(userId: String, action: String, extra: String?): String {
        val exists = transaction { UserTable.selectAll().where { UserTable.userId eq userId }.count() }
        if (exists == 0L) return "ERROR: user not found"

        return when (action) {
            "pubadmin" -> {
                transaction { UserTable.update({ UserTable.userId eq userId }) { it[globalRole] = "public_admin" } }
                log.info("CLI: user {} set to public_admin", userId)
                "OK: user $userId set to public_admin"
            }
            "member" -> {
                val currentRole = transaction { UserTable.selectAll().where { UserTable.userId eq userId }.singleOrNull()?.get(UserTable.globalRole) }
                if (currentRole == "owner") return "ERROR: cannot demote owner (use 'chown' first)"
                transaction { UserTable.update({ UserTable.userId eq userId }) { it[globalRole] = "member" } }
                log.info("CLI: user {} set to member", userId)
                "OK: user $userId set to member"
            }
            "admin" -> {
                val roomId = extra ?: return "ERROR: room_id required"
                // 确保用户是房间成员
                val isMember = transaction {
                    RoomTable.selectAll().where { RoomTable.roomId eq roomId }.count() > 0
                }
                if (!isMember) return "ERROR: room not found"
                val adminResult = roomManager.promoteToAdmin(roomId, userId)
                if (adminResult) {
                    log.info("CLI: user {} set as admin of room {}", userId, roomId)
                    "OK: user $userId set as admin of room $roomId"
                } else {
                    "ERROR: failed to promote (user may not be room member)"
                }
            }
            "chown" -> {
                val newOwnerId = extra ?: return "ERROR: new owner user_id required"
                val newOwnerExists = transaction { UserTable.selectAll().where { UserTable.userId eq newOwnerId }.count() }
                if (newOwnerExists == 0L) return "ERROR: new owner user not found"
                transaction {
                    // 降级原 owner
                    UserTable.update({ UserTable.globalRole eq "owner" }) { it[globalRole] = "public_admin" }
                    // 提升新 owner
                    UserTable.update({ UserTable.userId eq newOwnerId }) { it[globalRole] = "owner" }
                }
                log.info("CLI: ownership transferred from {} to {}", userId, newOwnerId)
                "OK: owner transferred from $userId to $newOwnerId (old owner → public_admin)"
            }
            else -> "ERROR: unknown action $action"
        }
    }

    // ═══════════════════════════════════════════════════════════

    fun roomAdd(roomId: String, createrId: String): String {
        val exists = transaction { RoomTable.selectAll().where { RoomTable.roomId eq roomId }.count() }
        if (exists > 0) return "ERROR: room_id already exists"
        val userExists = transaction { UserTable.selectAll().where { UserTable.userId eq createrId }.count() }
        if (userExists == 0L) return "ERROR: creater user not found"

        val result = roomManager.createRoomWithId(roomId, "CLI-Room-$roomId", createrId)
        return result.fold(
            onSuccess = {
                log.info("CLI: room {} created (creater: {})", roomId, createrId)
                "OK: room $roomId created (creater: $createrId, type: private)"
            },
            onFailure = { "ERROR: ${it.message}" }
        )
    }

    fun roomDel(roomId: String): String {
        val exists = transaction { RoomTable.selectAll().where { RoomTable.roomId eq roomId }.count() }
        if (exists == 0L) return "ERROR: room not found"
        roomManager.dissolveRoom(roomId, "CLI")
        log.info("CLI: room {} deleted", roomId)
        return "OK: room $roomId deleted"
    }

    fun roomMod(roomId: String, action: String, extra: String?): String {
        val exists = transaction { RoomTable.selectAll().where { RoomTable.roomId eq roomId }.count() }
        if (exists == 0L) return "ERROR: room not found"

        return when (action) {
            "setchat" -> {
                val mode = extra?.toIntOrNull() ?: return "ERROR: invalid chat mode (0=unlimit, 1=limit)"
                val type = if (mode == 0) RoomManager.ROOM_TYPE_UNLIMIT_CHAT else RoomManager.ROOM_TYPE_LIMIT_CHAT
                transaction {
                    RoomTable.update({ RoomTable.roomId eq roomId }) { it[RoomTable.roomType] = type }
                }
                val label = if (mode == 0) "UNLIMIT_CHAT" else "LIMIT_CHAT"
                log.info("CLI: room {} chat mode set to {}", roomId, label)
                "OK: room $roomId chat mode set to $label"
            }
            "setcreater" -> {
                val newCreaterId = extra ?: return "ERROR: new creater user_id required"
                val userExists = transaction { UserTable.selectAll().where { UserTable.userId eq newCreaterId }.count() }
                if (userExists == 0L) return "ERROR: new creater user not found"
                // 更换创建者
                transaction {
                    RoomTable.update({ RoomTable.roomId eq roomId }) { it[RoomTable.creatorId] = newCreaterId }
                }
                log.info("CLI: room {} creater changed to {}", roomId, newCreaterId)
                "OK: room $roomId creater changed to $newCreaterId"
            }
            else -> "ERROR: unknown action $action"
        }
    }

    // ═══════════════════════════════════════════════════════════

    fun fetchUser(userName: String): String {
        val users = transaction {
            UserTable.selectAll().where { UserTable.displayName like "%$userName%" }
                .map { it[UserTable.userId] to it[UserTable.displayName] }
        }
        if (users.isEmpty()) return "No users found matching '$userName'"
        return buildString {
            appendLine("Found ${users.size} user(s):")
            users.forEach { (id, name) -> appendLine("  $id  ($name)") }
        }
    }

    fun fetchRoom(roomName: String): String {
        val rooms = transaction {
            RoomTable.selectAll().where { RoomTable.name like "%$roomName%" }
                .map { Triple(it[RoomTable.roomId], it[RoomTable.name], it[RoomTable.creatorId]) }
        }
        if (rooms.isEmpty()) return "No rooms found matching '$roomName'"
        return buildString {
            appendLine("Found ${rooms.size} room(s):")
            rooms.forEach { (id, name, creator) -> appendLine("  $id  $name  (creater: $creator)") }
        }
    }
}
