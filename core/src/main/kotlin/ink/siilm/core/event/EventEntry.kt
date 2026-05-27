package ink.siilm.core.event

/**
 * 事件条目（Event ID 机制的核心数据类）。
 */
data class EventEntry(
    val eventId: String,
    val type: String,
    val initiatorId: String,
    val targetRoom: String?,
    val targetUser: String?,
    val payload: String?,
    val status: Int,
    val retryCount: Int = 0,
    val maxRetries: Int = 3,
    val scheduledAt: Long? = null,
    val createdAt: Long,
    val expiresAt: Long,
    val confirmedAt: Long? = null
)
