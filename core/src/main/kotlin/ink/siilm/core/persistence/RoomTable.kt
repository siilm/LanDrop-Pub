package ink.siilm.core.persistence

import org.jetbrains.exposed.sql.Table

/**
 * `rooms` 表 — 房间。
 *
 * room_type: 0=Owner创建, 1=Member私有, 2=Member公开
 * status:   1=正常, 2=已解散
 *
 * 对应 SQL: database-design.md §3.5
 */
object RoomTable : Table("rooms") {
    val id = integer("id").autoIncrement()
    val roomId = varchar("room_id", 16)                  // 6位短ID
    val name = varchar("name", 256)
    val creatorId = varchar("creator_id", 64)
    val roomType = integer("room_type").default(1)        // 0=Owner, 1=Member私有, 2=Member公开
    val status = integer("status").default(1)              // 1=正常, 2=已解散
    @Suppress("unused")
    val maxMembers = integer("max_members").default(500)
    val memberCount = integer("member_count").default(0)
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")

    override val primaryKey = PrimaryKey(id)

    init {
        index("idx_rooms_room_id", isUnique = true, roomId)
        index("idx_rooms_creator", isUnique = false, creatorId)
        index("idx_rooms_type_status", isUnique = false, roomType, status)
    }
}
