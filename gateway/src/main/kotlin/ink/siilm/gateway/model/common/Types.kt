package ink.siilm.gateway.model.common

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MessageElement(
    val type: String,
    val content: String? = null,
    @SerialName("file_id") val fileId: String? = null,
    @SerialName("file_name") val fileName: String? = null,
    @SerialName("file_size") val fileSize: Long? = null,
    @SerialName("mime_type") val mimeType: String? = null
)

@Serializable
data class FileAttachment(
    @SerialName("file_id") val fileId: String,
    @SerialName("file_name") val fileName: String,
    @SerialName("file_size") val fileSize: Long,
    @SerialName("mime_type") val mimeType: String? = null,
    @SerialName("checksum_sha256") val checksumSha256: String? = null,
    @SerialName("download_url") val downloadUrl: String? = null
)

@Serializable
data class RoomInfo(
    @SerialName("room_id") val roomId: String,
    @SerialName("name") val roomName: String,
    @SerialName("member_count") val memberCount: String,
    @SerialName("has_password") val hasPassword: Boolean
)
