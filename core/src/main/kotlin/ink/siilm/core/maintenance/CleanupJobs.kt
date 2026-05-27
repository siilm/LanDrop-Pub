package ink.siilm.core.maintenance

import ink.siilm.core.event.EventService
import ink.siilm.core.file.FileMetaManager
import ink.siilm.core.message.ChatHistoryService
import ink.siilm.core.room.RoomManager
import ink.siilm.core.persistence.RoomInviteTable
import ink.siilm.core.persistence.RoomJoinRequestTable
import ink.siilm.core.persistence.UserSessionTable
import ink.siilm.shared.config.LandropProperties
import kotlinx.coroutines.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.io.File
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class CleanupJobs(
    private val fileMetaManager: FileMetaManager,
    private val publicAdminCache: PublicAdminRoomCache,
    private val chatHistoryService: ChatHistoryService,
    private val eventService: EventService,
    private val scope: CoroutineScope,
    private val roomManager: RoomManager? = null
) {
    fun start() {
        scope.launch { scheduleFileCleanup() }
        scope.launch { scheduleRoomFileCleanup() }
        scope.launch { scheduleSessionCleanup() }
        scope.launch { schedulePublicAdminCacheRefresh() }
        scope.launch { scheduleRecalledMessagePurge() }
        scope.launch { scheduleCloudCacheCleanup() }
        scope.launch { scheduleExpiredInviteCleanup() }
        scope.launch { scheduleEventCleanup() }
        scope.launch { scheduleDissolvedRoomCleanup() }
        log.info("Cleanup jobs started")
    }

    private suspend fun scheduleFileCleanup() {
        while (currentCoroutineContext().isActive) {
            delay(60.minutes)
            try {
                val deleted = fileMetaManager.cleanupExpired()
                if (deleted > 0) log.warn("FileCleanupJob: removed {} expired files", deleted)
            } catch (e: Exception) { log.warn("FileCleanupJob error", e) }
        }
    }

    private suspend fun scheduleSessionCleanup() {
        while (currentCoroutineContext().isActive) {
            delay(60.minutes)
            try {
                val now = System.currentTimeMillis()
                val deleted = transaction {
                    val expired = UserSessionTable.selectAll().filter { it[UserSessionTable.expiresAt] < now }
                    expired.forEach { row -> UserSessionTable.deleteWhere { UserSessionTable.sessionId eq row[UserSessionTable.sessionId] } }
                    expired.size
                }
                if (deleted > 0) log.warn("SessionCleanupJob: removed {} expired sessions", deleted)
            } catch (e: Exception) { log.warn("SessionCleanupJob error", e) }
        }
    }

    private suspend fun schedulePublicAdminCacheRefresh() {
        while (currentCoroutineContext().isActive) {
            delay(5.hours)
            try { publicAdminCache.fullRefresh() }
            catch (e: Exception) { log.warn("PublicAdminCacheRefresh error", e) }
        }
    }

    private suspend fun scheduleRecalledMessagePurge() {
        while (currentCoroutineContext().isActive) {
            delay(60.minutes)
            try {
                val deleted = chatHistoryService.purgeRecalledMessages()
                if (deleted > 0) log.warn("RecalledMessagePurge: removed {} expired recalled messages", deleted)
            } catch (e: Exception) { log.warn("RecalledMessagePurge error", e) }
        }
    }

    private suspend fun scheduleCloudCacheCleanup() {
        while (currentCoroutineContext().isActive) {
            delay(14.days)
            try {
                val cacheDir = File(LandropProperties.getStorageCloudCacheDir())
                val cutoff = System.currentTimeMillis() - 14.days.inWholeMilliseconds
                var deleted = 0
                if (cacheDir.exists()) {
                    cacheDir.walkTopDown().forEach { file ->
                        if (file.isFile && file.lastModified() < cutoff) {
                            if (file.delete()) deleted++
                        }
                    }
                }
                if (deleted > 0) log.warn("CloudCacheCleanup: removed {} cached files older than 14 days", deleted)
            } catch (e: Exception) { log.warn("CloudCacheCleanup error", e) }
        }
    }

    private suspend fun scheduleExpiredInviteCleanup() {
        while (currentCoroutineContext().isActive) {
            delay(48.hours)
            try {
                val now = System.currentTimeMillis()
                transaction {
                    // 清理过期邀请
                    val expiredInvites = RoomInviteTable.selectAll().where {
                        RoomInviteTable.expiresAt lessEq now
                    }
                    expiredInvites.forEach { row ->
                        RoomInviteTable.deleteWhere { RoomInviteTable.id eq row[RoomInviteTable.id] }
                    }
                    val inviteCount = expiredInvites.count()
                    if (inviteCount > 0) log.warn("ExpiredRequestCleanup: removed {} expired invites", inviteCount)

                    // 清理过期加入申请
                    val expiredRequests = RoomJoinRequestTable.selectAll().where {
                        RoomJoinRequestTable.status eq 0 and (RoomJoinRequestTable.expiresAt lessEq now)
                    }
                    expiredRequests.forEach { row ->
                        RoomJoinRequestTable.deleteWhere { RoomJoinRequestTable.id eq row[RoomJoinRequestTable.id] }
                    }
                    val requestCount = expiredRequests.count()
                    if (requestCount > 0) log.warn("ExpiredRequestCleanup: removed {} expired join requests", requestCount)
                }
            } catch (e: Exception) { log.warn("ExpiredRequestCleanup error", e) }
        }
    }

    private suspend fun scheduleRoomFileCleanup() {
        while (currentCoroutineContext().isActive) {
            delay(60.minutes)
            try {
                val deleted = roomManager?.cleanupExpiredRoomFiles() ?: 0
                if (deleted > 0) log.warn("RoomFileCleanup: removed {} expired room files", deleted)
            } catch (e: Exception) { log.warn("RoomFileCleanup error", e) }
        }
    }

    private suspend fun scheduleEventCleanup() {
        while (currentCoroutineContext().isActive) {
            delay(60.minutes)
            try {
                val cleaned = eventService.cleanupExpired()
                if (cleaned > 0) log.warn("EventCleanup: marked {} expired event(s)", cleaned)
            } catch (e: Exception) { log.warn("EventCleanup error", e) }
        }
    }

    private suspend fun scheduleDissolvedRoomCleanup() {
        while (currentCoroutineContext().isActive) {
            delay(24.hours)
            try {
                val cutoff = System.currentTimeMillis() - 168.hours.inWholeMilliseconds
                val deleted = roomManager?.cleanupDissolvedRooms(cutoff) ?: 0
                if (deleted > 0) log.warn("DissolvedRoomCleanup: removed {} dissolved rooms", deleted)
            } catch (e: Exception) { log.warn("DissolvedRoomCleanup error", e) }
        }
    }

    companion object { private val log = LoggerFactory.getLogger(CleanupJobs::class.java) }
}
