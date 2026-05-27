package ink.siilm.core.persistence

import org.jetbrains.exposed.sql.Table

/**
 * `room_members` 表 — 房间成员关系。
 *
 * role: 0=Member, 1=Admin, 2=Creater
 * muted: 0=未禁言, 1=已禁言
 *
 * 对应 SQL: database-design.md §3.6
 */
object RoomMemberTable : Table("room_members") {
    val roomId = varchar("room_id", 16)
    val userId = varchar("user_id", 64)
    val displayName = varchar("display_name", 50).default("")  // 房间内头衔/昵称
    val role = integer("role").default(0)                  // 0=Member, 1=Admin, 2=Creater
    val muted = integer("muted").default(0)                // 0=未禁言, 1=已禁言
    val joinedAt = long("joined_at")

    override val primaryKey = PrimaryKey(roomId, userId)

    init {
        index("idx_rm_user", isUnique = false, userId)
    }
}
