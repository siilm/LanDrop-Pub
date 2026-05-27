package ink.siilm.core.persistence

import org.jetbrains.exposed.sql.Table

/**
 * `room_files` 表 — 房间文件（聊天图片/视频/音频等媒体）。
 *
 * 每个文件在每个房间独立一行。同一文件（相同 checksum）可在多个房间各有一行。
 */
object RoomFileTable : Table("room_files") {
    val id = long("id").autoIncrement()
    val roomId = varchar("room_id", 16)
    val fileId = varchar("file_id", 64)
    val fileName = varchar("file_name", 512)
    val fileSize = long("file_size")
    val mimeType = varchar("mime_type", 128).default("application/octet-stream")
    val storagePath = varchar("storage_path", 1024)
    val checksum = varchar("checksum", 128).nullable()
    val uploadedAt = long("uploaded_at")
    val expiresAt = long("expires_at").nullable()

    override val primaryKey = PrimaryKey(id)

    init {
        index("idx_rf_file_id", isUnique = false, fileId)
        index("idx_rf_room_id", isUnique = false, roomId)
        index("idx_rf_checksum", isUnique = false, checksum)
        index("idx_rf_expires", isUnique = false, expiresAt)
    }
}
