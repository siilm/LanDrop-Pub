package ink.siilm.core.auth

sealed class UpdateAvatarUrlResult {
    data class Success(val avatarUrl: String) : UpdateAvatarUrlResult()
    data object UserNotFound : UpdateAvatarUrlResult()
}
