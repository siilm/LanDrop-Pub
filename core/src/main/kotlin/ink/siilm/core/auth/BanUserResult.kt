package ink.siilm.core.auth

sealed class BanUserResult {
    data class Success(val deletedRooms: Int) : BanUserResult()
    data object UserNotFound : BanUserResult()
}
