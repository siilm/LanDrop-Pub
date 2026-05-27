package ink.siilm.core.persistence

import org.jetbrains.exposed.sql.Table

/**
 * `global_roles` 表 — 全局角色授权记录。
 *
 * 记录每个用户被授予的全局角色（owner / public_admin）。
 *
 * 对应 SQL: database-design.md §3.4
 */
object GlobalRoleTable : Table("global_roles") {
    val userId = varchar("user_id", 64)
    val role = varchar("role", 32)                         // 'owner' | 'public_admin'
    val grantedBy = varchar("granted_by", 64).nullable()
    val grantedAt = long("granted_at")

    override val primaryKey = PrimaryKey(userId)
}
