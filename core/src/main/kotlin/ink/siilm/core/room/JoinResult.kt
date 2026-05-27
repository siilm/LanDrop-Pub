package ink.siilm.core.room

sealed class JoinResult {
    data object Joined : JoinResult()
    data class Pending(val eventId: String) : JoinResult()
    data object RoomNotFound : JoinResult()
    data object RoomDissolved : JoinResult()
    data object AlreadyMember : JoinResult()
    data object RequestAlreadyPending : JoinResult()
}
