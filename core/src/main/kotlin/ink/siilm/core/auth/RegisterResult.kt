package ink.siilm.core.auth

sealed class RegisterResult {
    data class Success(val userId: String, val username: String, val globalRole: String, val privateKeyBase64: String, val publicKeyBase64: String) : RegisterResult()
    data object InvalidUserId : RegisterResult()
    data object UsernameTooLong : RegisterResult()
    data object UserIdAlreadyExists : RegisterResult()
}
