package ink.siilm.core.persistence

import org.jetbrains.exposed.sql.Table

/**
 * `room_invites` 表 — 邀请审批。
 *
 * status: 0=待审批, 1=已同意, 2=已拒绝
 *
 * 对应 SQL: database-design.md §3.7
 */
object RoomInviteTable : Table("room_invites") {
    val id = integer("id").autoIncrement()
    val roomId = varchar("room_id", 16)
    val inviterId = varchar("inviter_id", 64)
    val inviteeId = varchar("invitee_id", 64)
    val status = integer("status").default(0)              // 0=待审批, 1=已同意, 2=已拒绝
    val requestedAt = long("requested_at")
    val reviewedBy = varchar("reviewed_by", 64).nullable()
    val reviewedAt = long("reviewed_at").nullable()
    val expiresAt = long("expires_at").nullable()

    override val primaryKey = PrimaryKey(id)

    init {
        index("idx_invites_room_pending", isUnique = false, roomId, status)
        index("idx_invites_invitee", isUnique = false, inviteeId)
    }
}
