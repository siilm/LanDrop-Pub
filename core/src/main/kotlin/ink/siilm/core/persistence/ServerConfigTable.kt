package ink.siilm.core.persistence

import org.jetbrains.exposed.sql.Table

/**
 * `server_config` 表 — 全局配置键值对。
 *
 * 预置键:
 *   - max_rooms_per_member → '2'
 *   - allow_member_create_rooms → 'true'
 *
 * 对应 SQL: database-design.md §3.12
 */
object ServerConfigTable : Table("server_config") {
    val key = varchar("key", 128)
    val value = text("value")

    override val primaryKey = PrimaryKey(key)
}
