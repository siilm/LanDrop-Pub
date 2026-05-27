package ink.siilm.gateway.auth

import io.ktor.server.auth.*

/**
 * Ktor Authentication Principal — 承载 JWT claims。
 * 通过 call.principal<JwtPrincipal>() 获取。
 */
data class JwtPrincipal(val claims: JwtClaims) : Principal
