package ink.siilm.gateway.auth

import ink.siilm.shared.config.LandropProperties

/**
 * JWT 配置（gateway 验证端）。
 *
 * 统一从 landrop.properties 读取，与 core 签发端共享同一密钥。
 * core 负责签发 JWT（登录时），gateway 负责验证 JWT（每次请求）。
 */
data class JwtConfig(
    /** HMAC-SHA256 签名密钥（至少 256 位） */
    val secret: String = LandropProperties.getJwtProperties().secret,
    /** JWT 签发者标识 */
    val issuer: String = LandropProperties.getJwtProperties().issuer,
    /** access token 有效期（秒），默认 15 分钟 */
    val accessTokenTtlSeconds: Long = LandropProperties.getJwtProperties().accessTokenTtlSeconds,
    /** refresh token 有效期（秒），默认 14 天 */
    val refreshTokenTtlSeconds: Long = LandropProperties.getJwtProperties().refreshTokenTtlSeconds
)
