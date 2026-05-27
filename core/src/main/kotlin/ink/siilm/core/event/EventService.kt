package ink.siilm.core.event

import ink.siilm.core.persistence.EventTable
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory

/**
 * Event ID 服务 — 双向确认事件管理。
 *
 * 用于需客户端二次确认的高风险操作（解散房间、踢出 PUBLIC 用户等）。
 * 事件有 48h 过期时间，过期后自动标记为 status=3。
 */
class EventService {

    companion object {
        const val STATUS_PENDING = 0
        const val STATUS_CONFIRMED = 1
        const val STATUS_REJECTED = 2
        const val STATUS_EXPIRED = 3
        const val STATUS_PROCESSING = 4
        const val STATUS_FAILED_RETRYABLE = 5

        private val log = LoggerFactory.getLogger(EventService::class.java)
    }

    /**
     * 创建事件并返回 eventId。
     */
    fun createEvent(
        type: String,
        initiatorId: String,
        targetRoom: String? = null,
        targetUser: String? = null,
        payload: String? = null,
        expiresInMs: Long = 72 * 60 * 60 * 1000L, // 72h
        maxRetries: Int = 3,
        scheduledAt: Long? = null
    ): String {
        val eventId = java.util.UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        transaction {
            EventTable.insert {
                it[EventTable.eventId] = eventId
                it[EventTable.type] = type
                it[EventTable.initiatorId] = initiatorId
                it[EventTable.targetRoom] = targetRoom
                it[EventTable.targetUser] = targetUser
                it[EventTable.payload] = payload
                it[EventTable.status] = STATUS_PENDING
                it[EventTable.retryCount] = 0
                it[EventTable.maxRetries] = maxRetries
                it[EventTable.scheduledAt] = scheduledAt
                it[EventTable.createdAt] = now
                it[EventTable.expiresAt] = now + expiresInMs
            }
        }
        log.info("Event created: eventId={}, type={}, initiator={}", eventId, type, initiatorId)
        return eventId
    }

    /**
     * 确认事件。返回 EventResult 指示操作是否成功。
     */
    fun confirmEvent(eventId: String, userId: String): EventResult {
        return transaction {
            val event = EventTable.selectAll().where { EventTable.eventId eq eventId }
                .singleOrNull() ?: return@transaction EventResult.NotFound

            if (event[EventTable.status] != STATUS_PENDING) {
                return@transaction EventResult.AlreadyProcessed
            }
            val now = System.currentTimeMillis()
            if (event[EventTable.expiresAt] < now) {
                EventTable.update({ EventTable.eventId eq eventId }) {
                    it[status] = STATUS_EXPIRED
                }
                return@transaction EventResult.Expired
            }
            EventTable.update({ EventTable.eventId eq eventId }) {
                it[status] = STATUS_CONFIRMED
                it[confirmedAt] = now
            }
            log.info("Event confirmed: eventId={}, by={}", eventId, userId)
            EventResult.Confirmed(
                type = event[EventTable.type],
                targetRoom = event[EventTable.targetRoom],
                targetUser = event[EventTable.targetUser],
                payload = event[EventTable.payload]
            )
        }
    }

    /**
     * 拒绝事件。
     */
    fun rejectEvent(eventId: String, userId: String): EventResult {
        return transaction {
            val event = EventTable.selectAll().where { EventTable.eventId eq eventId }
                .singleOrNull() ?: return@transaction EventResult.NotFound

            if (event[EventTable.status] != STATUS_PENDING) {
                return@transaction EventResult.AlreadyProcessed
            }
            EventTable.update({ EventTable.eventId eq eventId }) {
                it[status] = STATUS_REJECTED
                it[confirmedAt] = System.currentTimeMillis()
            }
            log.info("Event rejected: eventId={}, by={}", eventId, userId)
            EventResult.Rejected
        }
    }

    /**
     * 将事件标记为可重试失败，递增 retry_count，设置下次重试时间。
     * @return 是否还有重试配额（retry_count < max_retries）
     */
    fun scheduleRetry(eventId: String, resultMsg: String? = null): Boolean {
        return transaction {
            val event = EventTable.selectAll().where { EventTable.eventId eq eventId }
                .singleOrNull() ?: return@transaction false
            val currentRetry = event[EventTable.retryCount]
            val maxRetries = event[EventTable.maxRetries]
            if (currentRetry >= maxRetries) {
                // 超过最大重试次数，标记为过期
                EventTable.update({ EventTable.eventId eq eventId }) {
                    it[status] = STATUS_EXPIRED
                    it[EventTable.resultMsg] = resultMsg ?: "max_retries_exceeded"
                }
                log.info("Event retries exhausted: eventId={}, retries={}/{}", eventId, currentRetry, maxRetries)
                return@transaction false
            }
            val backoff = (1L shl currentRetry) * 30_000L // 30s, 60s, 120s...
            val nextScheduledAt = System.currentTimeMillis() + backoff
            EventTable.update({ EventTable.eventId eq eventId }) {
                it[status] = STATUS_FAILED_RETRYABLE
                it[retryCount] = currentRetry + 1
                it[scheduledAt] = nextScheduledAt
                    it[EventTable.resultMsg] = resultMsg
            }
            log.info("Event scheduled retry: eventId={}, retry={}/{}, nextAt={}", eventId, currentRetry + 1, maxRetries, nextScheduledAt)
            true
        }
    }

    /**
     * 查询所有待处理事件（用于 Worker 轮询）。
     */
    fun pollPendingEvents(limit: Int = 10): List<EventEntry> {
        val now = System.currentTimeMillis()
        return transaction {
            val all = EventTable.selectAll().where {
                (EventTable.status eq STATUS_PENDING) and
                (EventTable.expiresAt greater now) and
                ((EventTable.scheduledAt eq null) or (EventTable.scheduledAt lessEq now))
            }.toList()
            all.sortedWith(compareBy({ it[EventTable.scheduledAt] ?: Long.MAX_VALUE }, { it[EventTable.createdAt] }))
               .take(limit)
               .map {
                   EventEntry(
                       eventId = it[EventTable.eventId],
                       type = it[EventTable.type],
                       initiatorId = it[EventTable.initiatorId],
                       targetRoom = it[EventTable.targetRoom],
                       targetUser = it[EventTable.targetUser],
                       payload = it[EventTable.payload],
                       status = it[EventTable.status],
                       retryCount = it[EventTable.retryCount],
                       maxRetries = it[EventTable.maxRetries],
                       scheduledAt = it[EventTable.scheduledAt],
                       createdAt = it[EventTable.createdAt],
                       expiresAt = it[EventTable.expiresAt]
                   )
               }
        }
    }

    /**
     * 清理过期事件。
     * @return 清理的条数
     */
    fun cleanupExpired(): Int {
        val now = System.currentTimeMillis()
        return transaction {
            val expired = EventTable.selectAll().where {
                (EventTable.status eq STATUS_PENDING) and (EventTable.expiresAt lessEq now)
            }.toList()
            expired.forEach { row ->
                EventTable.update({ EventTable.eventId eq row[EventTable.eventId] }) {
                    it[status] = STATUS_EXPIRED
                }
            }
            if (expired.isNotEmpty()) log.info("EventCleanup: marked {} event(s) as expired", expired.size)
            expired.size
        }
    }

}

sealed class EventResult {
    data class Confirmed(
        val type: String,
        val targetRoom: String?,
        val targetUser: String?,
        val payload: String?
    ) : EventResult()

    data object Rejected : EventResult()
    data object NotFound : EventResult()
    data object AlreadyProcessed : EventResult()
    data object Expired : EventResult()
}
