package ink.siilm.gateway.model.ws.server

import ink.siilm.gateway.model.common.FileAttachment
import ink.siilm.gateway.model.common.MessageElement
import ink.siilm.gateway.model.common.RoomInfo
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ChatMessageOut(
    val type: String = "chat_message",
    @SerialName("message_id") val messageId: String,
    val from: String,
    @SerialName("display_name") val displayName: String,
    val elements: List<MessageElement> = emptyList(),
    val content: String? = null,
    val file: FileAttachment? = null,
    @SerialName("room_id") val roomId: String? = null,
    val to: String? = null,
    val timestamp: Long
)

@Serializable
data class RoomEventOut(
    val type: String,
    @SerialName("user_id") val userId: String,
    @SerialName("room_id") val roomId: String? = null,
    @SerialName("room_name") val roomName: String? = null,
    val rooms: List<RoomInfo>? = null
)

@Serializable
data class FileInstructionOut(
    val type: String = "file_instruction",
    val command: String,
    @SerialName("file_id") val fileId: String,
    @SerialName("storage_path") val storagePath: String? = null,
    @SerialName("max_size") val maxSize: Long? = null,
    @SerialName("error_reason") val errorReason: String? = null
)

@Serializable
data class PongOut(
    val type: String = "pong",
    val timestamp: Long
)

@Serializable
data class AckOut(
    val type: String = "ack",
    @SerialName("message_id") val messageId: String,
    val status: String
)

@Serializable
data class ErrorOut(
    val type: String = "error",
    @SerialName("ref_message_id") val refMessageId: String? = null,
    @SerialName("error_code") val errorCode: Int,
    @SerialName("error_message") val errorMessage: String,
    @SerialName("error_type") val errorType: String? = null
)

@Serializable
data class ConnectedOut(
    val type: String = "connected",
    @SerialName("user_id") val userId: String,
    @SerialName("session_id") val sessionId: String
)

@Serializable
data class KickedOut(
    val type: String = "kicked",
    val reason: String? = null
)

@Serializable
data class PingOut(
    val type: String = "ping",
    val timestamp: Long
)

@Serializable
data class RecallOut(
    val type: String = "chat_recall",
    @SerialName("message_id") val messageId: String,
    @SerialName("room_id") val roomId: String? = null,
    val to: String? = null
)

@Serializable
data class EditOut(
    val type: String = "chat_edit",
    @SerialName("message_id") val messageId: String,
    val elements: String,
    @SerialName("room_id") val roomId: String? = null,
    val to: String? = null
)
