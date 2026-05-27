package ink.siilm.core.auth

sealed class PublicAdminResult {
    data class Success(val userId: String) : PublicAdminResult()
    data object NotOwner : PublicAdminResult()
    data object UserNotFound : PublicAdminResult()
}
