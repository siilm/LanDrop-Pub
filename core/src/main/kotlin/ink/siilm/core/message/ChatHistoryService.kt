package ink.siilm.core.message

import ink.siilm.core.auth.PermissionService
import ink.siilm.core.persistence.ChatMessageTable
import ink.siilm.core.room.RoomManager
import ink.siilm.shared.config.LandropProperties
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

/**
 * 聊天历史服务（C3，v2）。
 * 分页查询房间/私聊历史，软删除，支持未送达消息补推。
 */
class ChatHistoryService(
    private val permissionService: PermissionService? = null,
    private val roomManager: RoomManager? = null
) {

    /**
     * 分页查询房间聊天历史（排除已删除和撤回消息）。
     */
    fun getRoomMessages(roomId: String, before: Long = Long.MAX_VALUE, limit: Int = 50): List<MessageEntry> {
        return transaction {
            ChatMessageTable.selectAll()
                .where { (ChatMessageTable.roomId eq roomId) and (ChatMessageTable.createdAt.less(before)) }
                .andWhere { ChatMessageTable.deletedAt.isNull() }
                .andWhere { ChatMessageTable.status neq "recalled" }
                .orderBy(ChatMessageTable.createdAt, SortOrder.DESC)
                .limit(limit)
                .map { rowToEntry(it) }
                .reversed()
        }
    }

    /**
     * 分页查询私聊历史（排除已删除和撤回消息）。
     */
    fun getDirectMessages(userId1: String, userId2: String, before: Long = Long.MAX_VALUE, limit: Int = 50): List<MessageEntry> {
        return transaction {
            ChatMessageTable.selectAll()
                .where {
                    ((ChatMessageTable.fromUserId eq userId1) and (ChatMessageTable.toUserId eq userId2)) or
                    ((ChatMessageTable.fromUserId eq userId2) and (ChatMessageTable.toUserId eq userId1))
                }
                .andWhere { ChatMessageTable.createdAt.less(before) }
                .andWhere { ChatMessageTable.deletedAt.isNull() }
                .andWhere { ChatMessageTable.status neq "recalled" }
                .orderBy(ChatMessageTable.createdAt, SortOrder.DESC)
                .limit(limit)
                .map { rowToEntry(it) }
                .reversed()
        }
    }

    /**
     * 撤回消息（本人/管理员/Owner 可撤回）。
     * @return true 表示成功
     */
    fun recallMessage(messageId: String, userId: String): Boolean {
        return transaction {
            val msg = ChatMessageTable.selectAll()
                .where { ChatMessageTable.messageId eq messageId }
                .singleOrNull() ?: return@transaction false

            // 权限检查
            val allowed = permissionService?.canModifyMessage(userId, messageId)
                ?: (msg[ChatMessageTable.fromUserId] == userId)
            if (!allowed) {
                log.warn("User {} rejected recall message {}", userId, messageId)
                return@transaction false
            }

            val now = System.currentTimeMillis()
            ChatMessageTable.update({ ChatMessageTable.messageId eq messageId }) {
                it[deletedAt] = now
                it[status] = "recalled"
            }
            log.info("Message {} recalled by {}", messageId, userId)

            // 若消息含文件/图片元素，标记对应 room_files 为待过期清理
            val elements = msg[ChatMessageTable.elements]
            val roomId = msg[ChatMessageTable.roomId] ?: ""
            // 匹配 file_id 或旧格式 content（向后兼容）
            val fileIdRegex = Regex("\"(?:file_id|content)\"\\s*:\\s*\"([a-f0-9-]+)\"")
            val fileIds = fileIdRegex.findAll(elements).map { it.groupValues[1] }.toList()
            if (fileIds.isNotEmpty()) {
                val expirationMs = LandropProperties.getFileExpirationHours() * 3600 * 1000L
                val expiresAt = now + expirationMs
                for (fid in fileIds) {
                    roomManager?.markRoomFileExpiring(fid, roomId, expiresAt)
                    log.info("Marked room file {} expiring at {} (recall by {})", fid, expiresAt, userId)
                }
            }

            true
        }
    }

    /**
     * 编辑消息（本人/管理员/Owner 可编辑）。
     * @param newElements 新的 elements JSON 数组字符串
     * @return true 表示成功
     */
    fun editMessage(messageId: String, userId: String, newElements: String): Boolean {
        return transaction {
            val msg = ChatMessageTable.selectAll()
                .where { ChatMessageTable.messageId eq messageId }
                .singleOrNull() ?: return@transaction false

            // 富文本消息（含图片）禁止编辑
            val existingElements = msg[ChatMessageTable.elements]
            val hasRichMedia = existingElements.contains("\"type\":\"picture\"") ||
                               existingElements.contains("\"type\":\"image\"") ||
                               existingElements.contains("\"type\":\"file\"") ||
                               existingElements.contains("\"type\": \"picture\"") ||
                               existingElements.contains("\"type\": \"image\"") ||
                               existingElements.contains("\"type\": \"file\"")
            if (hasRichMedia) {
                log.info("Message {} edit rejected: contains picture/image element", messageId)
                return@transaction false
            }

            val allowed = permissionService?.canModifyMessage(userId, messageId)
                ?: (msg[ChatMessageTable.fromUserId] == userId)
            if (!allowed) {
                log.warn("User {} rejected edit message {}", userId, messageId)
                return@transaction false
            }

            val now = System.currentTimeMillis()
            ChatMessageTable.update({ ChatMessageTable.messageId eq messageId }) {
                it[elements] = newElements
                it[editedAt] = now
            }
            log.info("Message {} edited by {}", messageId, userId)
            true
        }
    }

    /**
     * 删除消息（兼容旧 API，现在等同于撤回）。
     * @return true 表示成功
     */
    fun deleteMessage(messageId: String, userId: String): Boolean = recallMessage(messageId, userId)

    /**
     * 清理已撤回超过 retainMs 毫秒的消息。
     * @return 删除的条数
     */
    fun purgeRecalledMessages(retainMs: Long = 24 * 60 * 60 * 1000L): Int {
        return transaction {
            val cutoff = System.currentTimeMillis() - retainMs
            val expired = ChatMessageTable.selectAll().where {
                ChatMessageTable.deletedAt.isNotNull() and (ChatMessageTable.deletedAt.less(cutoff))
            }.toList()
            expired.forEach { row ->
                ChatMessageTable.deleteWhere { ChatMessageTable.messageId eq row[ChatMessageTable.messageId] }
            }
            expired.size
        }
    }

    /**
     * 按 messageId 查询单条消息（用于聊天图片下载端点）。
     * @return 消息条目，未找到或已删除返回 null
     */
    fun getMessageById(messageId: String): MessageEntry? {
        return transaction {
            ChatMessageTable.selectAll().where {
                ChatMessageTable.messageId eq messageId
            }.singleOrNull()?.let { rowToEntry(it) }
        }
    }

    private fun rowToEntry(row: ResultRow) = MessageEntry(
        messageId = row[ChatMessageTable.messageId],
        fromUserId = row[ChatMessageTable.fromUserId],
        toUserId = row[ChatMessageTable.toUserId],
        roomId = row[ChatMessageTable.roomId],
        elements = row[ChatMessageTable.elements],
        replyToMsgId = row[ChatMessageTable.replyToMsgId],
        deliveryRequired = row[ChatMessageTable.deliveryRequired] == 1,
        status = row[ChatMessageTable.status],
        createdAt = row[ChatMessageTable.createdAt],
        editedAt = row[ChatMessageTable.editedAt],
        deletedAt = row[ChatMessageTable.deletedAt],
        storagePath = row[ChatMessageTable.storagePath],
        fileRefId = row[ChatMessageTable.fileRefId]
    )

    companion object {
        private val log = LoggerFactory.getLogger(ChatHistoryService::class.java)
    }
}
