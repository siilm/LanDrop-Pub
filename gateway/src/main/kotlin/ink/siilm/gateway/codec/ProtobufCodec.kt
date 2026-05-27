package ink.siilm.gateway.codec

import ink.siilm.proto.InternalComm.Envelope
import ink.siilm.shared.exception.GatewayException
import org.slf4j.LoggerFactory

/**
 * Protobuf 序列化/反序列化工具。
 *
 * 当前阶段（同进程架构）gateway 和 core 之间直接传递 Protobuf 对象，
 * 无需二进制编解码。本类主要用于：
 * - 未来跨进程传输（Rust gateway → Kotlin core via TCP/Socket）
 * - 持久化消息日志（写入数据库前序列化为 bytes）
 *
 * Envelope 的 Protobuf 序列化直接使用其内置方法：
 *   - envelope.toByteArray()  → 序列化
 *   - Envelope.parseFrom(bytes) → 反序列化
 *
 * 本类对此做薄封装，统一日志记录和错误处理。
 */
class ProtobufCodec {

    /**
     * 将 Protobuf Envelope 编码为字节数组。
     * 用于跨进程传输或消息持久化。
     */
    fun encodeToBytes(envelope: Envelope): ByteArray {
        return try {
            envelope.toByteArray()
        } catch (e: Exception) {
            log.error("Failed to encode Envelope messageId={}", envelope.messageId, e)
            throw GatewayException(
                message = "Protobuf encode failed: ${e.message}",
                errorCode = 2000,
                cause = e
            )
        }
    }

    /**
     * 从字节数组解码为 Protobuf Envelope。
     */
    fun decodeFromBytes(bytes: ByteArray): Envelope {
        return try {
            Envelope.parseFrom(bytes)
        } catch (e: Exception) {
            log.error("Failed to decode Envelope from {} bytes", bytes.size, e)
            throw GatewayException(
                message = "Protobuf decode failed: ${e.message}",
                errorCode = 2000,
                cause = e
            )
        }
    }

    /**
     * 将 Envelope 序列化为字节数组并计算大小（用于日志/监控）。
     */
    fun serializedSize(envelope: Envelope): Int {
        return envelope.serializedSize
    }

    companion object {
        private val log = LoggerFactory.getLogger(ProtobufCodec::class.java)
    }
}