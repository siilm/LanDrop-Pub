package ink.siilm.core.persistence

import org.jetbrains.exposed.sql.Table

/**
 * `files` 表 — 上传中转临时表。
 *
 * 文件上传请求暂存于此，校验完成后移入 `room_files`。
 * CleanupJobs 每 60 分钟清理 `expires_at < now` 的过期记录。
 */
object FileTable : Table("files") {
    val id = integer("id").autoIncrement()
    val roomId = varchar("room_id", 16)
    val fileId = varchar("file_id", 64)
    val fileHash = varchar("file_hash", 128)       // SHA-256
    val userId = varchar("user_id", 64)
    val startedAt = long("started_at")
    val expiresAt = long("expires_at")              // 48 小时后过期

    override val primaryKey = PrimaryKey(id)

    init {
        index("idx_files_file_id", isUnique = true, fileId)
        index("idx_files_expires", isUnique = false, expiresAt)
    }
}
