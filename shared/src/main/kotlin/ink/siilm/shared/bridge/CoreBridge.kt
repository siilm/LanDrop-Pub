package ink.siilm.shared.bridge

import ink.siilm.proto.InternalComm.Envelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import org.slf4j.LoggerFactory

/**
 * gateway ↔ core 的异步消息桥。
 *
 * 当前实现：同进程内通过 Channel 传递 Protobuf 对象。
 * 未来实现：通过 TCP/Unix Socket 收发 Protobuf 二进制字节。
 *
 * 接口设计刻意与传输方式无关——调用方只看到 send/receive。
 *
 * ## ADR-001
 * 本类放置于 shared 模块而非 gateway，确保 core 模块不依赖 gateway 的任何类。
 * CoreBridge 仅依赖 kotlinx.coroutines + Protobuf，不含 Ktor/Netty 引用。
 */
class CoreBridge(
    private val scope: CoroutineScope
) {
    private val toCoreChannel = Channel<Envelope>(capacity = CHANNEL_CAPACITY)
    private val fromCoreChannel = Channel<Envelope>(capacity = CHANNEL_CAPACITY)

    /**
     * gateway → core：发送内部消息。
     * 使用 trySend 而非 send，避免在 channel 满时挂起调用者。
     * 如果 channel 满（极端情况），记录错误并丢弃。
     */
    fun sendToCore(envelope: Envelope) {
        val result = toCoreChannel.trySend(envelope)
        if (result.isFailure) {
            log.warn("toCoreChannel full, dropping message: {}", envelope.messageId)
        }
    }

    /**
     * core → gateway：接收来自 core 的消息。
     * 以 Flow 形式暴露，gateway 用 collect 消费。
     */
    fun receiveFromCore(): Flow<Envelope> = fromCoreChannel.receiveAsFlow()

    /**
     * 供 core 模块调用，从 gateway 接收消息。
     * core 侧作为 toCoreChannel 的消费者。
     */
    fun coreInboundChannel(): Channel<Envelope> = toCoreChannel

    /**
     * 供 core 模块调用，向 gateway 发送消息。
     * core 侧作为 fromCoreChannel 的生产者。
     */
    fun coreOutboundChannel(): Channel<Envelope> = fromCoreChannel

    companion object {
        private val log = LoggerFactory.getLogger(CoreBridge::class.java)
        const val CHANNEL_CAPACITY = 256
    }
}