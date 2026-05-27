package ink.siilm.core.auth

sealed class VerifyResult {
    data class Success(
        val accessToken: String,
        val refreshToken: String,
        val expiresIn: Long,
        val refreshExpiresIn: Long,
        val userId: String,
        val username: String,
        val globalRole: String
    ) : VerifyResult()
    data object ChallengeExpired : VerifyResult()
    data object InvalidSignature : VerifyResult()
    data object UserNotFound : VerifyResult()
    data object UserInactive : VerifyResult()
}
