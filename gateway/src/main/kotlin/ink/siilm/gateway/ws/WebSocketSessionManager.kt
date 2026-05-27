package ink.siilm.gateway.ws

import ink.siilm.core.room.RoomManager
import ink.siilm.shared.bridge.CoreBridge
import ink.siilm.proto.InternalComm.Envelope
import ink.siilm.proto.InternalComm.SessionEvent
import io.ktor.websocket.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * WebSocket 会话管理器。
 *
 * 核心职责：
 * - 维护 userId ↔ WebSocketSession 的双向映射
 * - 保证同一 userId 仅保留最新连接（自动踢旧）
 * - 每会话 Mutex 防止并发帧交错
 * - 连接断开时自动清理
 *
 * 参照 [`gateway-design.md`](../../../../LanDrop-Doc/gateway-design.md) §5.1
 */
class WebSocketSessionManager(
    private val bridge: CoreBridge,
    private val roomManager: RoomManager
) {
    /** userId → UserSession */
    private val sessions = ConcurrentHashMap<String, UserSession>()

    /** sessionId → userId（反向映射，用于 O(1) 断连时定位） */
    private val sessionIdToUser = ConcurrentHashMap<String, String>()

    /** messageId → userId（用于 ACK/Error 出站时定位原始发送者） */
    private val messageIdToUser = ConcurrentHashMap<String, String>()

    /**
     * 会话元数据。
     */
    data class UserSession(
        val userId: String,
        val sessionId: String,
        val webSocketSession: WebSocketSession,
        val connectedAt: Instant,
        val mutex: Mutex = Mutex(),
        // 心跳状态
        var lastPongTime: Instant? = null,
        var pendingPingCount: Int = 0,
        var lastActivity: Instant = Instant.now()
    )

    /**
     * 注册新会话。原子操作，自动踢除同一 userId 的旧连接。
     *
     * @param userId 用户标识
     * @param sessionId 本次会话唯一 ID
     * @param wsSession Ktor WebSocketSession 实例
     */
    fun register(userId: String, sessionId: String, wsSession: WebSocketSession) {
        val newSession = UserSession(
            userId = userId,
            sessionId = sessionId,
            webSocketSession = wsSession,
            connectedAt = Instant.now()
        )

        // 原子操作：若已有旧连接，踢出旧连接
        val oldSession = sessions.compute(userId) { _, existing ->
            sessionIdToUser.put(sessionId, userId)
            if (existing != null) {
                log.warn("Kicking old session for userId={}, oldSessionId={}", userId, existing.sessionId)
                // 异步踢旧连接，避免阻塞 compute 操作
                Thread.startVirtualThread {
                    try {
                        kotlinx.coroutines.runBlocking {
                            existing.webSocketSession.close(
                                CloseReason(CloseReason.Codes.GOING_AWAY, "Replaced by new connection")
                            )
                        }
                        sessionIdToUser.remove(existing.sessionId)
                    } catch (e: Exception) {
                        log.debug("Old session already closed: {}", existing.sessionId)
                    }
                }

                // 通知 core：旧连接被踢
                val kickedEvent = Envelope.newBuilder()
                    .setMessageId(UUID.randomUUID().toString())
                    .setTimestamp(System.currentTimeMillis())
                    .setSessionId(existing.sessionId)
                    .setSessionEvent(
                        SessionEvent.newBuilder()
                            .setUserId(userId)
                            .setType(SessionEvent.Type.KICKED)
                    )
                    .build()
                bridge.sendToCore(kickedEvent)
            }
            newSession
        }
        log.info("Session registered: userId={}, sessionId={}, total={}", userId, sessionId, sessions.size)
    }

    /**
     * 注销会话。三重清理 sessions + sessionIdToUser + messageIdToUser。
     *
     * @param userId 用户标识
     */
    fun unregister(userId: String) {
        val session = sessions.remove(userId)
        if (session != null) {
            sessionIdToUser.remove(session.sessionId)
            // 清理该用户相关的消息追踪
            messageIdToUser.entries.removeIf { it.value == userId }
            log.info("Session unregistered: userId={}, sessionId={}, remaining={}", userId, session.sessionId, sessions.size)
        }
    }

    /**
     * 向指定用户发送文本帧。
     * 使用每会话 Mutex 防止多个协程并发写入导致帧交错。
     * 若发送失败（连接已断开），自动注销会话。
     *
     * @param userId 目标用户
     * @param text 要发送的 JSON 文本
     * @return true 发送成功，false 用户不在线或发送失败
     */
    suspend fun sendToUser(userId: String, text: String): Boolean {
        val session = sessions[userId] ?: return false

        return try {
            session.mutex.withLock {
                session.webSocketSession.send(Frame.Text(text))
            }
            log.debug(">>> [WS] sent to userId={}: {}", userId, text.take(200))
            true
        } catch (e: Exception) {
            log.warn("Failed to send to userId={}, disconnecting", userId, e)
            unregister(userId)
            try {
                session.webSocketSession.close()
            } catch (_: Exception) {}
            false
        }
    }

    /**
     * 检查用户是否在线（有活跃会话）。
     */
    fun isConnected(userId: String): Boolean {
        return sessions.containsKey(userId)
    }

    /** 收到任何 WebSocket 帧时更新活动时间。 */
    fun markActivity(userId: String) {
        sessions[userId]?.lastActivity = Instant.now()
    }

    /** 收到 pong 帧时清零未应答计数并更新 pong 时间。 */
    fun recordPong(userId: String) {
        sessions[userId]?.let {
            it.pendingPingCount = 0
            it.lastPongTime = Instant.now()
            it.lastActivity = Instant.now()
        }
    }

    /**
     * 发送 ping 前调用，递增未应答计数。
     * @return true 若超过最大丢失数，应断开连接
     */
    fun incrementPingAndCheck(userId: String, maxLost: Int): Boolean {
        val session = sessions[userId] ?: return false
        session.pendingPingCount++
        return session.pendingPingCount > maxLost
    }

    /**
     * 根据 userId 获取会话对象。O(1) 查找。
     *
     * @return UserSession 或 null（用户不在线）
     */
    fun getByUserId(userId: String): UserSession? {
        return sessions[userId]
    }

    /**
     * 根据 userId 获取其当前 sessionId。O(1) 查找。
     *
     * @return sessionId 或 null（用户不在线）
     */
    fun getSessionId(userId: String): String? {
        return sessions[userId]?.sessionId
    }

    /**
     * 根据 sessionId 获取 userId。
     */
    fun getUserIdBySessionId(sessionId: String): String? {
        return sessionIdToUser[sessionId]
    }

    /**
     * 获取指定房间内的在线用户 ID 列表。
     *
     * 当前阶段委托 core 模块提供，此处返回空列表作为降级。
     * 后续完善时由 core 的 RoomManager 负责追踪房间成员。
     */
    fun getUserIdsInRoom(roomId: String): List<String> {
        return roomManager.getMemberIds(roomId).toList()
    }

    /**
     * 记录消息 ID 与发送用户的映射，用于 ACK/Error 出站时路由回原始发送者。
     *
     * @param userId 发送该消息的用户
     * @param messageId 消息唯一标识
     */
    fun trackMessageId(userId: String, messageId: String) {
        messageIdToUser[messageId] = userId
    }

    /**
     * 根据消息 ID 查找原始发送用户。
     * 查找成功后移除映射（消息已送达 ACK/Error，不再需要追踪）。
     *
     * @param messageId 消息唯一标识（通常为 refMessageId）
     * @return userId 或 null（若映射不存在）
     */
    fun getUserIdByMessageId(messageId: String): String? {
        return messageIdToUser.remove(messageId)
    }

    /**
     * 获取当前在线用户总数。
     */
    fun activeCount(): Int = sessions.size

    /**
     * 向房间的所有管理员（role >= 1）推送通知。
     * @return 发送结果，包含失败的用户列表。
     */
    data class NotifyResult(val successCount: Int, val failedUserIds: List<String>)

    suspend fun notifyRoomAdmins(roomId: String, notification: String, excludeUserId: String? = null): NotifyResult {
        val members = roomManager.getMemberDetails(roomId)
        var successCount = 0
        val failedUserIds = mutableListOf<String>()
        for (member in members) {
            if (member.role < 1) continue
            if (member.userId == excludeUserId) continue
            if (sendToUser(member.userId, notification)) {
                successCount++
            } else {
                failedUserIds.add(member.userId)
            }
        }
        log.debug("notifyRoomAdmins room={} success={} failed={}", roomId, successCount, failedUserIds.size)
        return NotifyResult(successCount, failedUserIds)
    }

    companion object {
        private val log = LoggerFactory.getLogger(WebSocketSessionManager::class.java)
    }
}