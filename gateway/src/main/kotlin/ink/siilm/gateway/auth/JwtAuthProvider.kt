package ink.siilm.gateway.auth

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*

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
    }
}
