package ink.siilm.core.room

import ink.siilm.core.persistence.*
import ink.siilm.proto.InternalComm.RoomInfo
import ink.siilm.shared.constants.Constants
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * 房间管理器（数据库驱动版）。
 * B1: 基础 CRUD — createRoom / dissolveRoom / listRooms / getRoom（含配额检查）
 * B3: 成员角色管理 — appointAdmin / removeAdmin / kick / mute / transferCreater
 */
class RoomManager {
    private val onlineMembers = ConcurrentHashMap<String, MutableSet<String>>()

    // ═══════════════════════════════════════════════════════════
    // B1: 基础 CRUD
    // ═══════════════════════════════════════════════════════════

    fun createRoom(name: String, creatorUserId: String, roomType: Int = ROOM_TYPE_LIMIT_CHAT): Result<RoomInfo> {
        return transaction {
            // 配额检查
            val quota = getRoomQuota(creatorUserId)
            val createdCount = RoomTable.selectAll()
                .where { (RoomTable.creatorId eq creatorUserId) and (RoomTable.status eq STATUS_ACTIVE) }
                .count()
            if (createdCount >= quota) {
                return@transaction Result.failure(QuotaExceededException(quota, createdCount.toInt()))
            }
            val roomId = generateRoomId()
            val now = System.currentTimeMillis()
            val defaultDisplayName = UserTable.selectAll().where { UserTable.userId eq creatorUserId }
                .singleOrNull()?.get(UserTable.displayName) ?: creatorUserId
            RoomTable.insert {
                it[RoomTable.roomId] = roomId
                it[RoomTable.name] = name
                it[RoomTable.creatorId] = creatorUserId
                it[RoomTable.roomType] = roomType
                it[RoomTable.status] = STATUS_ACTIVE
                it[RoomTable.memberCount] = 1
                it[RoomTable.createdAt] = now
                it[RoomTable.updatedAt] = now
            }
            RoomMemberTable.insert {
                it[RoomMemberTable.roomId] = roomId
                it[RoomMemberTable.userId] = creatorUserId
                it[RoomMemberTable.displayName] = defaultDisplayName
                it[RoomMemberTable.role] = ROLE_CREATER
                it[RoomMemberTable.joinedAt] = now
            }
            onlineMembers.getOrPut(roomId) { ConcurrentHashMap.newKeySet() }.add(creatorUserId)
            log.info("Room created: roomId={}, name={}", roomId, name)

            // 非 owner 创建的房间写入 public_managed_rooms 快速查询表
            val isOwner = GlobalRoleTable.selectAll()
                .where { (GlobalRoleTable.userId eq creatorUserId) and (GlobalRoleTable.role eq "owner") }.count() > 0L
            if (!isOwner) {
                PublicManagedRoomTable.insert {
                    it[PublicManagedRoomTable.roomId] = roomId
                    it[PublicManagedRoomTable.addedAt] = now
                }
            }

            Result.success(RoomInfo.newBuilder().setRoomId(roomId).setRoomName(name).setMemberCount(1).setHasPassword(false).build())
        }
    }

    fun createRoomWithId(roomId: String, name: String, creatorUserId: String, roomType: Int = ROOM_TYPE_PUBLIC): Result<RoomInfo> {
        return transaction {
            if (RoomTable.selectAll().where { RoomTable.roomId eq roomId }.count() > 0L)
                return@transaction Result.failure(IllegalStateException("Room exists"))
            val now = System.currentTimeMillis()
            RoomTable.insert {
                it[RoomTable.roomId] = roomId; it[RoomTable.name] = name; it[RoomTable.creatorId] = creatorUserId
                it[RoomTable.roomType] = roomType; it[RoomTable.status] = STATUS_ACTIVE; it[RoomTable.memberCount] = 1
                it[RoomTable.createdAt] = now; it[RoomTable.updatedAt] = now
            }
            val defaultDisplayName = UserTable.selectAll().where { UserTable.userId eq creatorUserId }
                .singleOrNull()?.get(UserTable.displayName) ?: creatorUserId
            RoomMemberTable.insert {
                it[RoomMemberTable.roomId] = roomId; it[RoomMemberTable.userId] = creatorUserId
                it[RoomMemberTable.displayName] = defaultDisplayName
                it[RoomMemberTable.role] = ROLE_CREATER; it[RoomMemberTable.joinedAt] = now
            }
            // 非 owner 创建的房间写入 public_managed_rooms 快速查询表
            val isOwner = GlobalRoleTable.selectAll()
                .where { (GlobalRoleTable.userId eq creatorUserId) and (GlobalRoleTable.role eq "owner") }.count() > 0L
            if (!isOwner) {
                PublicManagedRoomTable.insert {
                    it[PublicManagedRoomTable.roomId] = roomId
                    it[PublicManagedRoomTable.addedAt] = now
                }
            }

            Result.success(RoomInfo.newBuilder().setRoomId(roomId).setRoomName(name).setMemberCount(1).setHasPassword(false).build())
        }
    }

    fun dissolveRoom(roomId: String, operatorUserId: String): Result<Unit> {
        return transaction {
            val room = RoomTable.selectAll().where { RoomTable.roomId eq roomId }.singleOrNull()
                ?: return@transaction Result.failure(NoSuchElementException("Room not found"))
            if (room[RoomTable.status] == STATUS_DISSOLVED)
                return@transaction Result.failure(IllegalStateException("Already dissolved"))
            val nonCreators = RoomMemberTable.selectAll().where { RoomMemberTable.roomId eq roomId }
                .filter { it[RoomMemberTable.role] != ROLE_CREATER }
            nonCreators.forEach { row -> RoomMemberTable.deleteWhere { (RoomMemberTable.roomId eq roomId) and (RoomMemberTable.userId eq row[RoomMemberTable.userId]) } }
            RoomTable.update({ RoomTable.roomId eq roomId }) {
                it[status] = STATUS_DISSOLVED; it[memberCount] = 1; it[updatedAt] = System.currentTimeMillis()
            }
            onlineMembers.remove(roomId)
            Result.success(Unit)
        }
    }

    fun listRooms(roomType: Int? = null): List<RoomInfo> {
        return transaction {
            val query = if (roomType != null) {
                RoomTable.selectAll().where { (RoomTable.status eq STATUS_ACTIVE) and (RoomTable.roomType eq roomType) }
            } else {
                RoomTable.selectAll().where { RoomTable.status eq STATUS_ACTIVE }
            }
            query.map {
                                RoomInfo.newBuilder().setRoomId(it[RoomTable.roomId]).setRoomName(it[RoomTable.name])
                    .setMemberCount(it[RoomTable.memberCount]).setHasPassword(false).build()
            }
        }
    }

    fun getRoom(roomId: String): RoomInfo? {
        return transaction {
            RoomTable.selectAll().where { RoomTable.roomId eq roomId }.singleOrNull()?.let {
                                RoomInfo.newBuilder().setRoomId(it[RoomTable.roomId]).setRoomName(it[RoomTable.name])
                    .setMemberCount(it[RoomTable.memberCount]).setHasPassword(false).build()
            }
        }
    }

    fun listRoomsForUser(userId: String): List<RoomInfo> {
        return transaction {
            val roomIds = RoomMemberTable.selectAll().where { RoomMemberTable.userId eq userId }
                .map { it[RoomMemberTable.roomId] }
            log.debug("listRoomsForUser userId={}, found memberships={}", userId, roomIds)
            val rooms = RoomTable.selectAll().where { (RoomTable.roomId inList roomIds) and (RoomTable.status eq STATUS_ACTIVE) }
                .map {
                    RoomInfo.newBuilder().setRoomId(it[RoomTable.roomId]).setRoomName(it[RoomTable.name])
                        .setMemberCount(it[RoomTable.memberCount]).setHasPassword(false).build()
                }
            log.debug("listRoomsForUser userId={}, active rooms={}", userId, rooms.map { it.roomId })
            rooms
        }
    }

    fun getMemberIds(roomId: String): Set<String> {
        return transaction {
            RoomMemberTable.selectAll().where { RoomMemberTable.roomId eq roomId }
                .map { it[RoomMemberTable.userId] }.toSet()
        }
    }

    fun getMemberDetails(roomId: String): List<MemberDetail> {
        return transaction {
            val members = RoomMemberTable.selectAll().where { RoomMemberTable.roomId eq roomId }.toList()
            members.map { rm ->
                val userRow = UserTable.selectAll().where { UserTable.userId eq rm[RoomMemberTable.userId] }
                    .singleOrNull()
                val avatar = userRow?.get(UserTable.avatarUrl) ?: ""
                val username = userRow?.get(UserTable.username) ?: ""
                MemberDetail(rm[RoomMemberTable.userId], rm[RoomMemberTable.displayName], username, rm[RoomMemberTable.role], avatar, rm[RoomMemberTable.muted])
            }
        }
    }

    /**
     * 强制加入房间（跳过审批，仅 owner/public_admin 可调用）。
     * 鉴权由调用方（gateway）负责。
     */
    fun forceJoinRoom(roomId: String, userId: String): Boolean = joinRoom(roomId, userId)

    fun joinRoom(roomId: String, userId: String): Boolean {
        return transaction {
            val room = RoomTable.selectAll().where { RoomTable.roomId eq roomId }.singleOrNull() ?: return@transaction false
            if (room[RoomTable.status] != STATUS_ACTIVE) return@transaction false
            val existing = RoomMemberTable.selectAll().where {
                (RoomMemberTable.roomId eq roomId) and (RoomMemberTable.userId eq userId)
            }.count()
            if (existing > 0) return@transaction true
            // 检测全局角色：owner/public_admin 加入房间时视为 creater
            val globalRole = GlobalRoleTable.selectAll().where { GlobalRoleTable.userId eq userId }
                .singleOrNull()?.get(GlobalRoleTable.role)
            val memberRole = if (globalRole == "owner" || globalRole == "public_admin") ROLE_CREATER else ROLE_MEMBER
            // displayName 默认值根据角色设定
            val defaultDisplayName = when (globalRole) {
                "owner" -> "Owner"
                "public_admin" -> "PubAdmin"
                else -> when (memberRole) {
                    ROLE_CREATER -> "Creater"
                    ROLE_ADMIN -> "Admin"
                    else -> "Member"
                }
            }
            RoomMemberTable.insert {
                it[RoomMemberTable.roomId] = roomId
                it[RoomMemberTable.userId] = userId
                it[RoomMemberTable.displayName] = defaultDisplayName
                it[RoomMemberTable.role] = memberRole
                it[RoomMemberTable.joinedAt] = System.currentTimeMillis()
            }
            RoomTable.update({ RoomTable.roomId eq roomId }) {
                with(SqlExpressionBuilder) { it[RoomTable.memberCount] = RoomTable.memberCount + 1 }
            }
            onlineMembers.getOrPut(roomId) { ConcurrentHashMap.newKeySet() }.add(userId)
            true
        }
    }

    fun leaveRoom(roomId: String, userId: String) {
        transaction {
            RoomMemberTable.deleteWhere { (RoomMemberTable.roomId eq roomId) and (RoomMemberTable.userId eq userId) }
            RoomTable.update({ RoomTable.roomId eq roomId }) {
                with(SqlExpressionBuilder) { it[RoomTable.memberCount] = RoomTable.memberCount - 1 }
            }
        }
        onlineMembers[roomId]?.remove(userId)
    }

    // ═══════════════════════════════════════════════════════════
    // B3: 成员角色管理
    // ═══════════════════════════════════════════════════════════

    fun appointAdmin(roomId: String, targetUserId: String): Boolean = transaction {
        RoomMemberTable.update({ (RoomMemberTable.roomId eq roomId) and (RoomMemberTable.userId eq targetUserId) }) { it[role] = ROLE_ADMIN } > 0
    }

    fun removeAdmin(roomId: String, targetUserId: String): Boolean = transaction {
        RoomMemberTable.update({ (RoomMemberTable.roomId eq roomId) and (RoomMemberTable.userId eq targetUserId) }) { it[role] = ROLE_MEMBER } > 0
    }

    fun kickMember(roomId: String, targetUserId: String) = leaveRoom(roomId, targetUserId)

    fun muteMember(roomId: String, targetUserId: String): Boolean = transaction {
        RoomMemberTable.update({ (RoomMemberTable.roomId eq roomId) and (RoomMemberTable.userId eq targetUserId) }) { it[muted] = 1 } > 0
    }

    fun unmuteMember(roomId: String, targetUserId: String): Boolean = transaction {
        RoomMemberTable.update({ (RoomMemberTable.roomId eq roomId) and (RoomMemberTable.userId eq targetUserId) }) { it[muted] = 0 } > 0
    }

    fun isMuted(roomId: String, userId: String): Boolean = transaction {
        RoomMemberTable.selectAll().where { (RoomMemberTable.roomId eq roomId) and (RoomMemberTable.userId eq userId) }
            .singleOrNull()?.get(RoomMemberTable.muted) == 1
    }

    fun transferCreater(roomId: String, newCreaterId: String): Boolean = transaction {
        RoomMemberTable.update({ (RoomMemberTable.roomId eq roomId) and (RoomMemberTable.role eq ROLE_CREATER) }) { it[role] = ROLE_MEMBER }
        val updated = RoomMemberTable.update({ (RoomMemberTable.roomId eq roomId) and (RoomMemberTable.userId eq newCreaterId) }) { it[role] = ROLE_CREATER }
        if (updated > 0) RoomTable.update({ RoomTable.roomId eq roomId }) { it[creatorId] = newCreaterId; it[updatedAt] = System.currentTimeMillis() }
        updated > 0
    }

    fun getMemberRole(roomId: String, userId: String): Int? = transaction {
        RoomMemberTable.selectAll().where { (RoomMemberTable.roomId eq roomId) and (RoomMemberTable.userId eq userId) }
            .singleOrNull()?.get(RoomMemberTable.role)
    }

    fun getCreatorId(roomId: String): String? = transaction {
        RoomTable.selectAll().where { RoomTable.roomId eq roomId }.singleOrNull()?.get(RoomTable.creatorId)
    }

    fun getRoomType(roomId: String): Int? = transaction {
        RoomTable.selectAll().where { RoomTable.roomId eq roomId }.singleOrNull()?.get(RoomTable.roomType)
    }

    fun isRoomActive(roomId: String): Boolean = transaction {
        RoomTable.selectAll().where { (RoomTable.roomId eq roomId) and (RoomTable.status eq STATUS_ACTIVE) }.count() > 0
    }

    // ═══════════════════════════════════════════════════════════
    // gateway 兼容
    // ═══════════════════════════════════════════════════════════

    fun notifyUserLeft(userId: String) { onlineMembers.values.forEach { it.remove(userId) } }

    /**
     * 获取用户在房间内的显示名（头衔/昵称）。
     * 若无记录，回退到 users.displayName 或 userId。
     */
    fun getDisplayName(roomId: String, userId: String): String {
        return transaction {
            RoomMemberTable.selectAll().where {
                (RoomMemberTable.roomId eq roomId) and (RoomMemberTable.userId eq userId)
            }.singleOrNull()?.get(RoomMemberTable.displayName)
        } ?: (transaction {
            UserTable.selectAll().where { UserTable.userId eq userId }
                .singleOrNull()?.get(UserTable.displayName)
        } ?: userId)
    }

    /**
     * 设置用户在房间内的显示名。
     * @return true 表示更新成功，false 表示该用户不在此房间中
     */
    fun setDisplayName(roomId: String, userId: String, newDisplayName: String): Boolean {
        return transaction {
            val updated = RoomMemberTable.update({
                (RoomMemberTable.roomId eq roomId) and (RoomMemberTable.userId eq userId)
            }) {
                it[RoomMemberTable.displayName] = newDisplayName
            }
            updated > 0
        }
    }

    /**
     * 设置其他用户在房间内的显示名（管理员/Owner 操作）。
     * @param operatorId 操作者 ID（用于日志）
     * @param targetUserId 目标用户 ID
     * @return true 表示更新成功
     */
    fun setOtherDisplayName(roomId: String, targetUserId: String, newDisplayName: String, operatorId: String): Boolean {
        val result = setDisplayName(roomId, targetUserId, newDisplayName)
        if (result) {
            log.info("DisplayName in room {} for user {} set to '{}' by {}", roomId, targetUserId, newDisplayName, operatorId)
        }
        return result
    }

    // ═══════════════════════════════════════════════════════════
    // 私有
    // ═══════════════════════════════════════════════════════════

    private fun generateRoomId(): String {
        val chars = Constants.ROOM_ID_CHARS
        var id: String
        do { id = (1..Constants.ROOM_ID_LENGTH).map { chars.random() }.joinToString("") }
        while (transaction { RoomTable.selectAll().where { RoomTable.roomId eq id }.count() } > 0)
        return id
    }

    private fun getRoomQuota(userId: String): Int {
        val globalRole = transaction { UserTable.selectAll().where { UserTable.userId eq userId }.singleOrNull()?.get(UserTable.globalRole) }
        if (globalRole == "owner" || globalRole == "public_admin") return Int.MAX_VALUE
        val extraQuota = transaction { UserTable.selectAll().where { UserTable.userId eq userId }.singleOrNull()?.get(UserTable.extraRoomQuota) ?: 0 }
        val defaultQuota = transaction { ServerConfigTable.selectAll().where { ServerConfigTable.key eq "max_rooms_per_member" }.singleOrNull()?.get(ServerConfigTable.value)?.toIntOrNull() ?: 2 }
        return defaultQuota + extraQuota
    }

    fun addRoomFile(roomId: String, fileName: String, fileSize: Long, mimeType: String, storagePath: String, checksum: String? = null, fileId: String? = null): String {
        val fid = fileId ?: java.util.UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        return transaction {
            RoomFileTable.insert {
                it[RoomFileTable.roomId] = roomId
                it[RoomFileTable.fileId] = fid
                it[RoomFileTable.fileName] = fileName
                it[RoomFileTable.fileSize] = fileSize
                it[RoomFileTable.mimeType] = mimeType
                it[RoomFileTable.storagePath] = storagePath
                if (checksum != null) it[RoomFileTable.checksum] = checksum
                it[RoomFileTable.uploadedAt] = now
            }
            log.info("Room file added: roomId={}, fileId={}, path={}", roomId, fid, storagePath)
            fid
        }
    }

    /** 秒传：从已有文件复制一行到新房间。 */
    fun copyRoomFileToRoom(sourceFileId: String, targetRoomId: String): String? {
        return transaction {
            val src = RoomFileTable.selectAll().where { RoomFileTable.fileId eq sourceFileId }.firstOrNull()
                ?: return@transaction null
            val newFileId = java.util.UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            RoomFileTable.insert {
                it[RoomFileTable.roomId] = targetRoomId
                it[RoomFileTable.fileId] = newFileId
                it[RoomFileTable.fileName] = src[RoomFileTable.fileName]
                it[RoomFileTable.fileSize] = src[RoomFileTable.fileSize]
                it[RoomFileTable.mimeType] = src[RoomFileTable.mimeType]
                it[RoomFileTable.storagePath] = src[RoomFileTable.storagePath]  // 复用磁盘文件
                it[RoomFileTable.checksum] = src[RoomFileTable.checksum]
                it[RoomFileTable.uploadedAt] = now
            }
            log.info("Room file copied (instant): srcFileId={}, newFileId={}, roomId={}", sourceFileId, newFileId, targetRoomId)
            newFileId
        }
    }

    fun listRoomFiles(roomId: String): List<RoomFileEntry> {
        return transaction {
            RoomFileTable.selectAll().where {
                (RoomFileTable.roomId eq roomId) and
                (RoomFileTable.storagePath notLike "%/chatimg/%")  // 排除聊天图片
            }
                .map { RoomFileEntry(it[RoomFileTable.roomId], it[RoomFileTable.fileId], it[RoomFileTable.fileName], it[RoomFileTable.fileSize], it[RoomFileTable.mimeType], it[RoomFileTable.storagePath], it[RoomFileTable.uploadedAt]) }
        }
    }

    fun getRoomFileByFileIdAndRoomId(fileId: String, roomId: String): RoomFileEntry? {
        return transaction {
            RoomFileTable.selectAll().where { (RoomFileTable.fileId eq fileId) and (RoomFileTable.roomId eq roomId) }
                .singleOrNull()?.let {
                    RoomFileEntry(it[RoomFileTable.roomId], it[RoomFileTable.fileId], it[RoomFileTable.fileName], it[RoomFileTable.fileSize], it[RoomFileTable.mimeType], it[RoomFileTable.storagePath], it[RoomFileTable.uploadedAt])
                }
        }
    }

    fun getAnyRoomFileByFileId(fileId: String): RoomFileEntry? {
        return transaction {
            RoomFileTable.selectAll().where { RoomFileTable.fileId eq fileId }
                .firstOrNull()?.let {
                    RoomFileEntry(it[RoomFileTable.roomId], it[RoomFileTable.fileId], it[RoomFileTable.fileName], it[RoomFileTable.fileSize], it[RoomFileTable.mimeType], it[RoomFileTable.storagePath], it[RoomFileTable.uploadedAt])
                }
        }
    }

    fun markRoomFileExpiring(fileId: String, roomId: String, expiresAt: Long): Boolean {
        return transaction {
            val updated = RoomFileTable.update({ (RoomFileTable.fileId eq fileId) and (RoomFileTable.roomId eq roomId) }) {
                it[RoomFileTable.expiresAt] = expiresAt
            }
            updated > 0
        }
    }

    fun cleanupExpiredRoomFiles(): Int {
        val now = System.currentTimeMillis()
        return transaction {
            val expired = RoomFileTable.selectAll().filter {
                val exp = it[RoomFileTable.expiresAt] ?: return@filter false
                exp < now
            }
            expired.forEach { row ->
                try { java.io.File(row[RoomFileTable.storagePath]).delete() } catch (_: Exception) {}
                RoomFileTable.deleteWhere { RoomFileTable.id eq row[RoomFileTable.id] }
            }
            if (expired.isNotEmpty()) log.warn("RoomFileCleanup: removed {} expired room file(s)", expired.size)
            expired.size
        }
    }

    fun findRoomFileByChecksum(checksum: String): RoomFileEntry? {
        if (checksum.isBlank()) return null
        return transaction {
            RoomFileTable.selectAll().where { RoomFileTable.checksum eq checksum }.firstOrNull()?.let {
                RoomFileEntry(it[RoomFileTable.roomId], it[RoomFileTable.fileId], it[RoomFileTable.fileName],
                    it[RoomFileTable.fileSize], it[RoomFileTable.mimeType], it[RoomFileTable.storagePath],
                    it[RoomFileTable.uploadedAt])
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 权限管理: 晋升/降级 Admin
    // ═══════════════════════════════════════════════════════════

    /**
     * 晋升成员为 Admin（仅当被晋升者 role=0 且操作者权限等级 >= 2）。
     */
    fun promoteToAdmin(roomId: String, targetUserId: String): Boolean {
        return transaction {
            val target = RoomMemberTable.selectAll().where {
                (RoomMemberTable.roomId eq roomId) and (RoomMemberTable.userId eq targetUserId)
            }.singleOrNull() ?: return@transaction false
            if (target[RoomMemberTable.role] != ROLE_MEMBER) return@transaction false
            RoomMemberTable.update({
                (RoomMemberTable.roomId eq roomId) and (RoomMemberTable.userId eq targetUserId)
            }) {
                it[role] = ROLE_ADMIN
            }
            true
        }
    }

    /**
     * 降级 Admin 为 Member（仅当被降级者 role=1 且操作者权限等级 >= 2）。
     */
    fun demoteFromAdmin(roomId: String, targetUserId: String): Boolean {
        return transaction {
            val target = RoomMemberTable.selectAll().where {
                (RoomMemberTable.roomId eq roomId) and (RoomMemberTable.userId eq targetUserId)
            }.singleOrNull() ?: return@transaction false
            if (target[RoomMemberTable.role] != ROLE_ADMIN) return@transaction false
            RoomMemberTable.update({
                (RoomMemberTable.roomId eq roomId) and (RoomMemberTable.userId eq targetUserId)
            }) {
                it[role] = ROLE_MEMBER
            }
            true
        }
    }

    /**
     * 清理已解散超过指定时间的房间及其关联数据。
     * 删除聊天记录、文件记录、成员记录、邀请/申请记录，最后删除房间。
     * 磁盘文件保留（除非无其他房间引用）。
     * @param cutoffTime 解散时间早于此值的房间将被清理
     * @return 清理的房间数
     */
    fun cleanupDissolvedRooms(cutoffTime: Long): Int {
        return transaction {
            val dissolvedRooms = RoomTable.selectAll().where {
                (RoomTable.status eq STATUS_DISSOLVED) and (RoomTable.updatedAt lessEq cutoffTime)
            }.toList()

            dissolvedRooms.forEach { room ->
                val roomId = room[RoomTable.roomId]
                // 删除聊天消息
                ChatMessageTable.deleteWhere { ChatMessageTable.roomId eq roomId }
                // 删除房间文件记录（保留磁盘文件）
                RoomFileTable.deleteWhere { RoomFileTable.roomId eq roomId }
                // 删除剩余成员记录
                RoomMemberTable.deleteWhere { RoomMemberTable.roomId eq roomId }
                // 删除邀请/申请记录
                RoomInviteTable.deleteWhere { RoomInviteTable.roomId eq roomId }
                RoomJoinRequestTable.deleteWhere { RoomJoinRequestTable.roomId eq roomId }
                // 从 public_managed_rooms 移除
                PublicManagedRoomTable.deleteWhere { PublicManagedRoomTable.roomId eq roomId }
                // 最后删除房间
                RoomTable.deleteWhere { RoomTable.roomId eq roomId }
            }

            if (dissolvedRooms.isNotEmpty()) log.warn("DissolvedRoomCleanup: removed {} rooms", dissolvedRooms.size)
            dissolvedRooms.size
        }
    }

    companion object {
        const val ROLE_MEMBER = 0; const val ROLE_ADMIN = 1; const val ROLE_CREATER = 2
        const val STATUS_ACTIVE = 1; const val STATUS_DISSOLVED = 2
        // room_type (v2): 0=Unlimit Chat, 1=Limit Chat, 2=Public Room
        const val ROOM_TYPE_UNLIMIT_CHAT = 0; const val ROOM_TYPE_LIMIT_CHAT = 1; const val ROOM_TYPE_PUBLIC = 2
        // legacy aliases (will be removed)
        @Deprecated("Use ROOM_TYPE_UNLIMIT_CHAT") const val ROOM_TYPE_OWNER = 0
        @Deprecated("Use ROOM_TYPE_LIMIT_CHAT") const val ROOM_TYPE_MEMBER_PRIVATE = 1
        @Deprecated("Use ROOM_TYPE_PUBLIC") const val ROOM_TYPE_MEMBER_PUBLIC = 2
        private val log = LoggerFactory.getLogger(RoomManager::class.java)
    }
}

class QuotaExceededException(max: Int, current: Int) : RuntimeException("Room quota exceeded: max=$max, current=$current")