package ink.siilm.core.room

sealed class ApproveResult {
    data object Success : ApproveResult()
    data class ReferredToAdmin(val eventId: String) : ApproveResult()
    data object RequestNotFound : ApproveResult()
    data object AlreadyProcessed : ApproveResult()
}
