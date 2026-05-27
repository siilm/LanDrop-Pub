package ink.siilm.core.message

import ink.siilm.core.persistence.ChatMessageTable
import ink.siilm.core.room.RoomManager
import ink.siilm.core.user.UserManager
import ink.siilm.proto.InternalComm
import ink.siilm.proto.InternalComm.Envelope
import ink.siilm.shared.bridge.CoreBridge
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 消息路由器（C2）。
 * 私聊/群聊路由 + 禁言检查 + 持久化 + 离线入队。
 */
class MessageRouter(
    private val bridge: CoreBridge,
    private val roomManager: RoomManager,
    private val userManager: UserManager,
    private val processors: List<MessageProcessor>
) {
    // userId → 离线消息队列
    private val offlineQueue = ConcurrentHashMap<String, ConcurrentLinkedQueue<Envelope>>()

    suspend fun route(envelope: Envelope) {
        val delivery = envelope.messageDelivery

        // 处理器链
        for (processor in processors) {
            if (!processor.process(delivery)) return
        }

        // 禁言检查（群聊）
        if (delivery.hasRoomId()) {
            val roomId = delivery.roomId
            if (roomManager.isMuted(roomId, delivery.fromUserId)) {
                log.warn("Muted user {} attempted to send message in {}", delivery.fromUserId, roomId)
                return
            }
        }

        // 持久化
        val messageId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        // 使用 delivery.elements（网关传入的原样 elements JSON），若无则从 text_content 降级
        val elementsJson = delivery.elements.ifBlank {
            val escaped = delivery.textContent.replace("\\", "\\\\").replace("\"", "\\\"")
            "[{\"type\":\"text\",\"content\":\"$escaped\"}]"
        }
        transaction {
            ChatMessageTable.insert {
                it[ChatMessageTable.messageId] = messageId
                it[ChatMessageTable.fromUserId] = delivery.fromUserId
                it[ChatMessageTable.toUserId] = if (delivery.hasToUserId()) delivery.toUserId else null
                it[ChatMessageTable.roomId] = if (delivery.hasRoomId()) delivery.roomId else null
                it[ChatMessageTable.elements] = elementsJson
                it[ChatMessageTable.deliveryRequired] = 0
                it[ChatMessageTable.createdAt] = now
                it[ChatMessageTable.status] = "sent"
                // 仅当消息含图片时写入 storage_path
                if (delivery.storagePath.isNotBlank()) {
                    it[ChatMessageTable.storagePath] = delivery.storagePath
                }
                if (delivery.fileRefId.isNotBlank()) {
                    it[ChatMessageTable.fileRefId] = delivery.fileRefId
                }
                if (delivery.fileRefId.isNotBlank()) {
                    log.info("Message {} file_ref_id={} storage_path={}", messageId, delivery.fileRefId, delivery.storagePath)
                }
            }
        }

        // 投递
        val targetUserIds = when {
            delivery.hasRoomId() -> {
                roomManager.getMemberIds(delivery.roomId) - delivery.fromUserId
            }
            delivery.hasToUserId() -> listOf(delivery.toUserId)
            else -> emptyList()
        }

        // 注入 display_name 到 fromUsername
        val displayName = if (delivery.hasRoomId()) {
            roomManager.getDisplayName(delivery.roomId, delivery.fromUserId)
        } else {
            null  // 私聊暂不处理
        }
        val outEnvelope = if (displayName != null) {
            val deliveryBuilder = InternalComm.MessageDelivery.newBuilder()
                .setFromUserId(delivery.fromUserId)
                .setFromUsername(displayName)
                .setTextContent(delivery.textContent)
                .setContentType(delivery.contentType)
            if (delivery.hasRoomId()) deliveryBuilder.roomId = delivery.roomId
            if (delivery.hasToUserId()) deliveryBuilder.toUserId = delivery.toUserId
            if (delivery.hasFileRef()) deliveryBuilder.fileRef = delivery.fileRef
            Envelope.newBuilder()
                .setMessageId(envelope.messageId)
                .setTimestamp(envelope.timestamp)
                .setSessionId(envelope.sessionId)
                .setMessageDelivery(deliveryBuilder)
                .build()
        } else {
            envelope
        }

        for (userId in targetUserIds) {
            if (userManager.isOnline(userId)) {
                bridge.coreOutboundChannel().send(outEnvelope)
            } else {
                // 离线入队
                offlineQueue.getOrPut(userId) { ConcurrentLinkedQueue<Envelope>() }.add(outEnvelope)
                log.debug("Offline message queued for {}", userId)
            }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(MessageRouter::class.java)
    }
}
