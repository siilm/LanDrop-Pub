package ink.siilm.gateway.auth

import io.ktor.http.*
import io.ktor.server.auth.*

/**
 * Ktor Authentication Provider — 从 Authorization: Bearer <token> 头验证 JWT。
 *
 * 配置名: "jwt-auth"
 * 使用: authenticate("jwt-auth") { ... }
 * 获取用户: call.principal<JwtPrincipal>()
 */
fun AuthenticationConfig.jwtAuth(validator: JwtValidator) {
    bearer(name = "jwt-auth") {
        realm = "LanDrop"
        authenticate { credential ->
            val token = credential.token
            val claims = validator.validate(token)
            if (claims != null && !claims.isExpired) {
                JwtPrincipal(claims)
            } else {
                null
            }
        }
        // CORS 预检（OPTIONS）请求跳过 JWT Bearer 认证，
        // 由路由层的全局 options 处理器接管并返回 CORS 头。
        skipWhen { call ->
            call.request.local.method == HttpMethod.Options
        }
    }
}
