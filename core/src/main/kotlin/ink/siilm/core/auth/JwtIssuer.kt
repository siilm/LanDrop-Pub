package ink.siilm.core.auth

import ink.siilm.shared.config.LandropProperties
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.slf4j.LoggerFactory
import java.util.Date
import java.util.UUID
import javax.crypto.SecretKey

/**
 * JWT access token 签发器（core 侧）。
 *
 * 使用 HMAC-SHA256 对称密钥签名，与 gateway 的 JwtValidator 共享同一密钥。
 *
 * JWT Claims:
 * - sub: userId
 * - user_id: users.user_id
 * - session_id: user_sessions.session_id
 * - device_id: trust_devices.device_id
 * - global_role: owner | public_admin | member
 * - iat: 签发时间
 * - exp: 过期时间 (15 min)
 * - jti: JWT 唯一 ID
 */
class JwtIssuer(
    private val jwtProps: LandropProperties.JwtProperties = LandropProperties.getJwtProperties()
) {

    private val key: SecretKey by lazy {
        Keys.hmacShaKeyFor(jwtProps.secret.toByteArray(Charsets.UTF_8))
    }

    /**
     * 签发 JWT access token。
     *
     * @param userId 用户 ID
     * @param username 用户名
     * @param sessionId 会话 ID
     * @param deviceId 设备指纹（可选）
     * @param globalRole 全局角色（默认 member）
     * @return 签发的 JWT 字符串
     */
    fun issue(
        userId: String,
        username: String,
        sessionId: String,
        deviceId: String? = null,
        globalRole: String = "member"
    ): String {
        val now = System.currentTimeMillis()
        val expiration = now + jwtProps.accessTokenTtlSeconds * 1000

        val builder = Jwts.builder()
            .issuer(jwtProps.issuer)
            .subject(userId)
            .claim("user_id", userId)
            .claim("session_id", sessionId)
            .claim("global_role", globalRole)
            .id(UUID.randomUUID().toString())
            .issuedAt(Date(now))
            .expiration(Date(expiration))

        if (deviceId != null) {
            builder.claim("device_id", deviceId)
        }

        val token = builder.signWith(key).compact()

        log.debug("JWT issued: sub={}, userId={}, sessionId={}, exp={}", username, userId, sessionId, expiration)
        return token
    }

    /**
     * 获取 access token TTL（秒）。
     */
    val accessTokenTtlSeconds: Long get() = jwtProps.accessTokenTtlSeconds

    companion object {
        private val log = LoggerFactory.getLogger(JwtIssuer::class.java)
    }
}
