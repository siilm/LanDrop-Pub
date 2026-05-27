package ink.siilm.shared.exception

/**
 * 项目统一异常基类。
 * 使用 open class 允许跨模块构造具体异常实例。
 */
open class LanDropException(
    message: String,
    val errorCode: Int = 0,
    cause: Throwable? = null
) : RuntimeException(message, cause)

// ---- Core 异常 ----
open class CoreException(
    message: String,
    errorCode: Int = 1000,
    cause: Throwable? = null
) : LanDropException(message, errorCode, cause)

class RoomNotFoundException(roomId: String) :
    CoreException("Room not found: $roomId", 1001)

class UserOfflineException(userId: String) :
    CoreException("User offline: $userId", 1002)

class FileTooLargeException(actualSize: Long, maxSize: Long) :
    CoreException("File size $actualSize exceeds limit $maxSize", 1003)

class InvalidMessageException(reason: String) :
    CoreException("Invalid message: $reason", 1004)

// ---- Gateway 异常 ----
open class GatewayException(
    message: String,
    errorCode: Int = 2000,
    cause: Throwable? = null
) : LanDropException(message, errorCode, cause)

class SessionExpiredException(sessionId: String) :
    GatewayException("Session expired: $sessionId", 2001)

class RateLimitException(userId: String) :
    GatewayException("Rate limit exceeded for user: $userId", 2002)

class InvalidFrameException(detail: String) :
    GatewayException("Invalid WebSocket frame: $detail", 2003)