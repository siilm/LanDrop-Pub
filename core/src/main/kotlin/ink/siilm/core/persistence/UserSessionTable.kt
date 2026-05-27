package ink.siilm.core.persistence

import org.jetbrains.exposed.sql.Table

/**
 * `user_sessions` 表 — 会话 token。
 *
 * 每个 refresh token 对应一条记录，access token 为 JWT 不存表。
 * refresh_token 仅存 SHA-256 哈希。
 *
 * 对应 SQL: database-design.md §3.2
 */
object UserSessionTable : Table("user_sessions") {
    val id = integer("id").autoIncrement()
    val sessionId = varchar("session_id", 64)
    val userId = varchar("user_id", 64)
    val refreshTokenHash = varchar("refresh_token_hash", 64)   // SHA-256(refresh_token)
    val deviceId = varchar("device_id", 128).nullable()
    val createdAt = long("created_at")
    val expiresAt = long("expires_at")
    val lastUsedAt = long("last_used_at")
    val revoked = integer("revoked").default(0)

    override val primaryKey = PrimaryKey(id)

    init {
        index("idx_sessions_session_id", isUnique = true, sessionId)
        index("idx_sessions_user_id", isUnique = false, userId)
        index("idx_sessions_refresh_hash", isUnique = false, refreshTokenHash)
        index("idx_sessions_expires", isUnique = false, expiresAt)
        index("idx_sessions_revoked", isUnique = false, revoked)
    }
}
