package ink.siilm.core.persistence

import org.jetbrains.exposed.sql.Table

/**
 * `public_managed_rooms` 预计算表。
 *
 * 缓存 PublicAdmin 可管辖的 Member 房间 ID 列表。
 * 定时任务全量刷新，避免实时多表 JOIN。
 *
 * 对应 SQL: database-design.md §3.11
 */
object PublicManagedRoomTable : Table("public_managed_rooms") {
    val roomId = varchar("room_id", 16)
    val addedAt = long("added_at")

    override val primaryKey = PrimaryKey(roomId)
}
