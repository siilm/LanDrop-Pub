package ink.siilm.core.auth

sealed class RefreshResult {
    data class Success(val accessToken: String, val expiresIn: Long) : RefreshResult()
    data object InvalidToken : RefreshResult()
    data object TokenExpired : RefreshResult()
    data object TokenRevoked : RefreshResult()
    data object UserNotFound : RefreshResult()
}
