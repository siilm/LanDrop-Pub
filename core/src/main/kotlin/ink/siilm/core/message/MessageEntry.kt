package ink.siilm.core.message

/**
 * 聊天消息条目（core 内部使用，与 gateway 的 Responses.MessageEntry 不同）。
 */
data class MessageEntry(
    val messageId: String,
    val fromUserId: String,
    val toUserId: String?,
    val roomId: String?,
    val elements: String,            // JSON 数组字符串
    val replyToMsgId: String?,
    val deliveryRequired: Boolean,
    val status: String,
    val createdAt: Long,
    val editedAt: Long?,
    val deletedAt: Long?,
    val storagePath: String? = null,  // 聊天图片/文件消息的存储路径（兼容旧消息）
    val fileRefId: String? = null     // 多媒体消息关联的 room_files.file_id
)
