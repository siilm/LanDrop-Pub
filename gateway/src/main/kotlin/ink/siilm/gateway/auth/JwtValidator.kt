package ink.siilm.gateway.auth

import io.jsonwebtoken.Claims
import io.jsonwebtoken.Jws
import io.jsonwebtoken.JwtException
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.slf4j.LoggerFactory
import javax.crypto.SecretKey

/**
 * JWT access token 验证器。
 *
 * gateway 在 HTTP 请求和 WebSocket 连接时调用此验证器，
 * 本地验证 JWT 签名与过期时间，提取 claims 后放行。
 * 不调用 core。
 */
class JwtValidator(private val config: JwtConfig) {

    private val key: SecretKey by lazy {
        Keys.hmacShaKeyFor(config.secret.toByteArray(Charsets.UTF_8))
    }

    /**
     * 验证 JWT 并返回 claims。
     *
     * @param token JWT 字符串（不含 "Bearer " 前缀）
     * @return claims 或 null（无效/过期/签名错误）
     */
    fun validate(token: String): JwtClaims? {
        return try {
            val jws: Jws<Claims> = Jwts.parser()
                .verifyWith(key)
                .requireIssuer(config.issuer)
                .build()
                .parseSignedClaims(token)

            val body = jws.payload
            JwtClaims(
                subject = body.subject ?: return null,
                userId = body["user_id", String::class.java] ?: return null,
                sessionId = body["session_id", String::class.java] ?: return null,
                deviceId = body["device_id", String::class.java],
                globalRole = body["global_role", String::class.java] ?: "member",
                jwtId = body.id ?: return null,
                issuedAt = body.issuedAt?.time ?: 0,
                expiration = body.expiration?.time ?: 0
            )
        } catch (e: JwtException) {
            log.debug("JWT validation failed: {}", e.message)
            null
        } catch (e: Exception) {
            log.warn("Unexpected JWT validation error", e)
            null
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(JwtValidator::class.java)
    }
}

/**
 * 从 JWT 提取的 claims。
 */
data class JwtClaims(
    val subject: String,         // userId (JWT sub claim)
    val userId: String,          // users.user_id (与 subject 相同)
    val sessionId: String,       // user_sessions.session_id
    val deviceId: String?,       // trust_devices.device_id
    val globalRole: String,      // owner | public_admin | member
    val jwtId: String,           // JWT 唯一 ID
    val issuedAt: Long,          // epoch millis
    val expiration: Long         // epoch millis
) {
    val isExpired: Boolean get() = System.currentTimeMillis() > expiration
}
