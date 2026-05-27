package ink.siilm.shared.constants

/**
 * 全局常量定义。
 */
object Constants {
    /** 默认服务端口 */
    const val DEFAULT_PORT = 8080

    /** 文件块传输缓冲区大小（64KB） */
    const val FILE_CHUNK_SIZE = 64 * 1024

    /** 内部消息队列容量 */
    const val MESSAGE_QUEUE_CAPACITY = 256

    /** 文件过期时间（毫秒），默认 24 小时 */
    const val FILE_EXPIRATION_MS: Long = 24 * 60 * 60 * 1000

    /** 心跳间隔（毫秒） */
    const val HEARTBEAT_INTERVAL_MS: Long = 15_000

    /** 心跳超时（毫秒） */
    const val HEARTBEAT_TIMEOUT_MS: Long = 30_000

    /** 房间 ID 字符集（去除易混淆字符） */
    const val ROOM_ID_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    /** 房间 ID 长度 */
    const val ROOM_ID_LENGTH = 6
}