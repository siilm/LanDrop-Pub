package ink.siilm.core.auth

sealed class LoginInitiateResult {
    data class Success(val tempSessionId: String, val challenge: String) : LoginInitiateResult()
    data object UserNotFound : LoginInitiateResult()
    data object UserInactive : LoginInitiateResult()
}
