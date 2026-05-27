package ink.siilm.core.auth

sealed class RenameUsernameResult {
    data class Success(val newUsername: String) : RenameUsernameResult()
    data object UsernameTooLong : RenameUsernameResult()
    data object UserNotFound : RenameUsernameResult()
}
