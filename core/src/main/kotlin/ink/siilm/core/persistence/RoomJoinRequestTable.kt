package ink.siilm.core.persistence

import org.jetbrains.exposed.sql.Table

/**
 * `room_join_requests` 表 — 加入申请。
 *
 * status: 0=待审批, 1=已同意, 2=已拒绝
 *
 * 对应 SQL: database-design.md §3.8
 */
object RoomJoinRequestTable : Table("room_join_requests") {
    val id = integer("id").autoIncrement()
    val roomId = varchar("room_id", 16)
    val applicantId = varchar("applicant_id", 64)
    val status = integer("status").default(0)              // 0=待审批, 1=已同意, 2=已拒绝
    val message = text("message").nullable()
    val appliedAt = long("applied_at")
    val expiresAt = long("expires_at").nullable()
    val reviewedBy = varchar("reviewed_by", 64).nullable()
    val reviewedAt = long("reviewed_at").nullable()

    override val primaryKey = PrimaryKey(id)

    init {
        index("idx_joinreq_room_pending", isUnique = false, roomId, status)
        index("idx_joinreq_applicant", isUnique = false, applicantId)
    }
}
