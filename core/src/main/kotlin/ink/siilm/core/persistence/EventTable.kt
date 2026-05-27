package ink.siilm.core.persistence

import org.jetbrains.exposed.sql.Table

/**
 * `events` 表 — 事件跟踪（event_id 双向确认机制）+ 异步任务 Worker 支持。
 *
 * status: 0=待确认, 1=已确认, 2=已拒绝, 3=已过期
 *         4=处理中, 5=失败(可重试) — 用于异步任务 Worker
 */
object EventTable : Table("events") {
    val id = integer("id").autoIncrement()
    val eventId = varchar("event_id", 64)
    val type = varchar("type", 32)
    val initiatorId = varchar("initiator_id", 64)
    val targetRoom = varchar("target_room", 16).nullable()
    val targetUser = varchar("target_user", 64).nullable()
    val payload = text("payload").nullable()
    val status = integer("status").default(0)
    val retryCount = integer("retry_count").default(0)
    val maxRetries = integer("max_retries").default(3)
    val scheduledAt = long("scheduled_at").nullable()
    val createdAt = long("created_at")
    val expiresAt = long("expires_at")
    val confirmedAt = long("confirmed_at").nullable()      // 同 processed_at
    val resultMsg = text("result_msg").nullable()
    val version = integer("version").default(0)

    override val primaryKey = PrimaryKey(id)

    init {
        index("idx_events_event_id", isUnique = true, eventId)
        index("idx_events_status", isUnique = false, status, expiresAt)
        index("idx_events_status_scheduled", isUnique = false, status, scheduledAt)
    }
}
