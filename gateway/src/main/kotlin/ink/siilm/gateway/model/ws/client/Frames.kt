package ink.siilm.gateway.model.ws.client

import ink.siilm.gateway.model.common.MessageElement
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ChatMessageIn(
    val type: String,  // "chat_message"
    val content: String,
    val elements: List<MessageElement>? = null,
    @SerialName("room_id") val roomId: String? = null,
    val to: String? = null,
    @SerialName("delivery_required") val deliveryRequired: Boolean = false
)

@Serializable
data class RoomCreateIn(
    val type: String,  // "room_create"
    val name: String? = null,
    @SerialName("room_name") val roomName: String? = null,
    val password: String? = null
)

@Serializable
data class RoomJoinIn(
    val type: String,  // "room_join"
    @SerialName("room_id") val roomId: String,
    val password: String? = null
)

@Serializable
data class RoomLeaveIn(
    val type: String,  // "room_leave"
    @SerialName("room_id") val roomId: String
)

@Serializable
data class PongIn(
    val type: String  // "pong"
)

@Serializable
data class AckIn(
    val type: String,  // "ack"
    @SerialName("message_id") val messageId: String,
    val status: String = "ok"
)
