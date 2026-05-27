package ink.siilm.core.auth

import ink.siilm.core.persistence.ChatMessageTable
import ink.siilm.core.persistence.UserTable
import ink.siilm.core.room.RoomManager
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * 权限判定服务（B4）。
 *
 * 权限分级（由高到低）：
 *   owner > public_admin = creater > admin > member
 *
 * - owner: 全局全权限
 * - public_admin: 管理所有 member 创建的房间，视为 creater
 * - creater: 仅在其创建的房间内拥有全权限，在其他房间视为 member
 * - admin: 本房间管理权限（由 creater 任命）
 * - member: 普通成员
 *
 * public_admin 只能由 owner 晋升。
 */
class PermissionService(private val roomManager: RoomManager) {

    /**
     * 检查用户是否可以解散房间。
     * 允许：Owner、管理此房间的 PublicAdmin、本房间 Creater
     */
    fun canDissolveRoom(userId: String, roomId: String): Boolean {
        if (isOwner(userId)) return true
        if (isPublicAdmin(userId) && canPublicAdminManageRoom(userId, roomId)) return true
        val creatorId = roomManager.getCreatorId(roomId) ?: return false
        return creatorId == userId
    }

    /**
     * 检查用户是否可以任命/解除 Admin。
     * 允许：Owner、管理此房间的 PublicAdmin、本房间 Creater
     */
    fun canManageAdmin(operatorId: String, roomId: String): Boolean {
        if (isOwner(operatorId)) return true
        if (isPublicAdmin(operatorId) && canPublicAdminManageRoom(operatorId, roomId)) return true
        val memberRole = roomManager.getMemberRole(roomId, operatorId) ?: return false
        return memberRole == RoomManager.ROLE_CREATER
    }

    /**
     * 检查用户是否可以踢出/禁言成员。
     * 允许：Owner、管理此房间的 PublicAdmin、本房间 Creater、本房间 Admin
     */
    fun canManageMember(operatorId: String, roomId: String): Boolean {
        if (isOwner(operatorId)) return true
        if (isPublicAdmin(operatorId) && canPublicAdminManageRoom(operatorId, roomId)) return true
        val memberRole = roomManager.getMemberRole(roomId, operatorId) ?: return false
        return memberRole == RoomManager.ROLE_CREATER || memberRole == RoomManager.ROLE_ADMIN
    }

    /**
     * 检查 PublicAdmin 是否可管理此房间。
     * 条件：global_role = public_admin && room_type = Member房间
     */
    fun canPublicAdminManageRoom(operatorId: String, roomId: String): Boolean {
        val globalRole = getGlobalRole(operatorId) ?: return false
        if (globalRole != "public_admin") return false
        val roomType = roomManager.getRoomType(roomId) ?: return false
        return roomType == RoomManager.ROOM_TYPE_MEMBER_PRIVATE || roomType == RoomManager.ROOM_TYPE_MEMBER_PUBLIC
    }

    /**
     * 获取用户的全局角色。
     */
    fun getGlobalRole(userId: String): String? {
        return transaction {
            UserTable.selectAll().where { UserTable.userId eq userId }
                .singleOrNull()?.get(UserTable.globalRole)
        }
    }

    /**
     * 是否为 Owner。
     */
    fun isOwner(userId: String): Boolean {
        return getGlobalRole(userId) == "owner"
    }

    /**
     * 是否为 PublicAdmin。
     */
    fun isPublicAdmin(userId: String): Boolean {
        return getGlobalRole(userId) == "public_admin"
    }

    /**
     * 检查用户是否可以修改/撤回消息。
     * 允许：Owner、管理此房间的 PublicAdmin、操作者 role >= 发送者 role
     */
    fun canModifyMessage(userId: String, messageId: String): Boolean {
        if (isOwner(userId)) return true
        return transaction {
            val msg = ChatMessageTable.selectAll().where { ChatMessageTable.messageId eq messageId }.singleOrNull()
                ?: return@transaction false
            val roomId = msg[ChatMessageTable.roomId] ?: return@transaction false
            val senderId = msg[ChatMessageTable.fromUserId]

            // PublicAdmin 管理此房间
            if (isPublicAdmin(userId) && canPublicAdminManageRoom(userId, roomId)) return@transaction true

            // 消息发送者本人
            if (senderId == userId) return@transaction true

            // 操作者 role >= 发送者 role
            val operatorLevel = getRoleLevel(userId, roomId)
            val senderLevel = getRoleLevel(senderId, roomId)
            operatorLevel >= senderLevel
        }
    }

    /**
     * 检查用户是否可以修改他人房间内的头衔。
     * 允许：Owner、管理此房间的 PublicAdmin、本房间 Creater/Admin
     */
    fun canModifyOtherDisplayName(operatorId: String, roomId: String): Boolean {
        if (isOwner(operatorId)) return true
        if (isPublicAdmin(operatorId) && canPublicAdminManageRoom(operatorId, roomId)) return true
        val memberRole = roomManager.getMemberRole(roomId, operatorId) ?: return false
        return memberRole == RoomManager.ROLE_CREATER || memberRole == RoomManager.ROLE_ADMIN
    }

    private fun getMessageRoomId(messageId: String): String? {
        return transaction {
            ChatMessageTable.selectAll().where { ChatMessageTable.messageId eq messageId }
                .singleOrNull()?.get(ChatMessageTable.roomId)
        }
    }

    /**
     * 获取用户在指定房间的权限等级。
     * 3=Owner, 2=PublicAdmin/Creater, 1=Admin, 0=Member
     */
    fun getRoleLevel(userId: String, roomId: String): Int {
        if (isOwner(userId)) return 3
        if (isPublicAdmin(userId) && canPublicAdminManageRoom(userId, roomId)) return 2
        val memberRole = roomManager.getMemberRole(roomId, userId) ?: return 0
        return when (memberRole) {
            RoomManager.ROLE_CREATER -> 2
            RoomManager.ROLE_ADMIN -> 1
            else -> 0
        }
    }

    /**
     * 检查用户是否可以在指定房间发布公告。
     * 需要 Creater/PublicAdmin/Owner 权限 (level >= 2)
     */
    fun canBroadcast(userId: String, roomId: String): Boolean {
        return getRoleLevel(userId, roomId) >= 2
    }

}
