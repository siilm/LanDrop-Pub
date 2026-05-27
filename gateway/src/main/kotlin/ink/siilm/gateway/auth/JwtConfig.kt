package ink.siilm.gateway.auth

/**
 * JWT 配置。
 *
 * v3 认证体系中，gateway 持有 JWT 签名密钥，可独立验证 access token 而无需调 core。
 * core 负责签发 JWT（登录时），gateway 负责验证 JWT（每次请求）。
 */
data class JwtConfig(
    /** HMAC-SHA256 签名密钥（至少 256 位）。默认从 ServerConfig 或环境变量获取。 */
    val secret: String = System.getenv("LANDROP_JWT_SECRET") ?: "landrop-dev-secret-change-in-production-32bytes!!",
    /** JWT 签发者标识 */
    val issuer: String = "landrop",
    /** access token 有效期（秒），默认 15 分钟 */
    val accessTokenTtlSeconds: Long = 900,
    /** refresh token 有效期（秒），默认 14 天 */
    val refreshTokenTtlSeconds: Long = 1_209_600
)
