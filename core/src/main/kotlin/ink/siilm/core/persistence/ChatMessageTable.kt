package ink.siilm.core.persistence

import org.jetbrains.exposed.sql.Table

/**
 * `chat_messages` 表 — 聊天历史（v2）。
 *
 * 内容由 `elements` JSON 数组统一承载，替代旧 content_type + text_content + file_ref_id。
 * 新增 delivery_required / edited_at / deleted_at / status 支持消息生命周期。
 *
 * 对应 SQL: databse-design-new.md §3.9
 */
object ChatMessageTable : Table("chat_messages") {
    val id = long("id").autoIncrement()
    val messageId = varchar("message_id", 64)
    val fromUserId = varchar("from_user_id", 64)
    val toUserId = varchar("to_user_id", 64).nullable()
    val roomId = varchar("room_id", 16).nullable()
    val elements = text("elements")                          // JSON 数组
    val replyToMsgId = varchar("reply_to_msg_id", 64).nullable()
    val deliveryRequired = integer("delivery_required").default(0)
    val createdAt = long("created_at")
    val editedAt = long("edited_at").nullable()
    val deletedAt = long("deleted_at").nullable()
    val status = varchar("status", 16).default("sent")       // sent | delivered | read | recalled | failed
    val storagePath = text("storage_path").nullable()        // 聊天图片/文件消息的存储路径（兼容旧消息）
    val fileRefId = varchar("file_ref_id", 64).nullable()   // 多媒体消息关联的 room_files.file_id

    override val primaryKey = PrimaryKey(id)

    init {
        index("idx_msg_message_id", isUnique = true, messageId)
        index("idx_msg_room_time", isUnique = false, roomId, createdAt)
        index("idx_msg_reply_to", isUnique = false, replyToMsgId)
        index("idx_msg_deleted_at", isUnique = false, deletedAt)
        index("idx_msg_delivery", isUnique = false, deliveryRequired, status)
    }
}
