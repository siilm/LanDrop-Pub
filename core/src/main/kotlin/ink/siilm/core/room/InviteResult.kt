package ink.siilm.core.room

sealed class InviteResult {
    data class Success(val eventId: String) : InviteResult()
    data object DirectJoined : InviteResult()
    data object RoomNotFound : InviteResult()
    data object NotMember : InviteResult()
    data object AlreadyMember : InviteResult()
    data object AlreadyInvited : InviteResult()
}
