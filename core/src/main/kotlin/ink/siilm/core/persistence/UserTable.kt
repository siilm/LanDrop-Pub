package ink.siilm.core.persistence

import org.jetbrains.exposed.sql.Table

/**
 * `users` 表 — 用户账户。
 *
 * 对应 SQL: database-design.md §3.1
 */
object UserTable : Table("users") {
    val id = integer("id").autoIncrement()
    val userId = varchar("user_id", 64)
    val username = varchar("username", 128)
    val publicKey = text("public_key")                    // Ed25519 Base64 公钥
    val displayName = varchar("display_name", 256).default("")
    val globalRole = varchar("global_role", 32).default("member")  // owner | public_admin | member
    val extraRoomQuota = integer("extra_room_quota").default(0)
    val createdAt = long("created_at")
    val lastLogin = long("last_login").nullable()
    val avatarUrl = varchar("avatar_url", 512).default("NONE_URL")  // 头像 URL（图床上传后存储）
    val isActive = integer("is_active").default(1)

    override val primaryKey = PrimaryKey(id)

    init {
        // username 不再唯一（允许用户自行更改，可重名）
        index("idx_users_username", isUnique = false, username)
        index("idx_users_user_id", isUnique = true, userId)
        index("idx_users_global_role", isUnique = false, globalRole)
    }
}
