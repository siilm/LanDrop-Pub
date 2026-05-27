package ink.siilm.gateway.model.http.response

import ink.siilm.gateway.model.common.RoomInfo
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class HealthResponse(val status: String = "ok")

@Serializable
data class ServerStatusResponse(
    val status: String = "ok",
    val online: String
)

@Serializable
data class RegisterResponse(
    @SerialName("user_id") val userId: String,
    val username: String,
    @SerialName("global_role") val globalRole: String
)

@Serializable
data class LoginInitResponse(
    @SerialName("temp_session_id") val tempSessionId: String,
    val challenge: String
)

@Serializable
data class VerifyResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_in") val expiresIn: String,
    @SerialName("refresh_expires_in") val refreshExpiresIn: String,
    @SerialName("user_id") val userId: String,
    val username: String,
    @SerialName("global_role") val globalRole: String
)

@Serializable
data class RefreshResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("expires_in") val expiresIn: String
)

@Serializable
data class LogoutResponse(val status: String = "logged_out")

@Serializable
data class RenameUsernameResponse(val username: String)

@Serializable
data class RoomListResponse(
    val count: String,
    val rooms: List<RoomInfo>
)

@Serializable
data class RoomCreateResponse(
    @SerialName("room_id") val roomId: String,
    val name: String
)

@Serializable
data class RoomMembersResponse(
    @SerialName("room_id") val roomId: String,
    val count: String,
    val members: List<MemberDetail>
)

@Serializable
data class MemberDetail(
    @SerialName("user_id") val userId: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("username") val username: String = "",
    @SerialName("avatar_url") val avatarUrl: String = "",
    val role: String,
    val muted: Int = 0
)

@Serializable
data class JoinRoomResponse(
    val status: String,
    @SerialName("room_id") val roomId: String? = null
)

@Serializable
data class RoomMessagesResponse(
    @SerialName("room_id") val roomId: String,
    val count: String,
    val messages: List<MessageEntry>
)

@Serializable
data class MessageEntry(
    @SerialName("message_id") val messageId: String,
    val from: String,
    val elements: kotlinx.serialization.json.JsonElement,
    val status: String,
    @SerialName("created_at") val createdAt: String
)

@Serializable
data class ConfigGetResponse(
    val key: String,
    val value: String
)

@Serializable
data class ConfigSetResponse(val status: String = "updated")

@Serializable
data class AdminListResponse(
    val count: String,
    val admins: List<AdminEntry>
)

@Serializable
data class AdminEntry(
    @SerialName("user_id") val userId: String,
    val username: String
)

@Serializable
data class AdminAppointResponse(
    val status: String = "appointed",
    @SerialName("user_id") val userId: String
)

@Serializable
data class AdminRemoveResponse(val status: String = "removed")

@Serializable
data class FileUploadResponse(
    @SerialName("file_id") val fileId: String,
    val status: String = "ready"
)

@Serializable
data class DeleteMessageResponse(val status: String = "deleted")

@Serializable
data class DisplayNameResponse(
    val success: Boolean,
    @SerialName("display_name") val displayName: String
)

@Serializable
data class AvatarUploadResponse(
    val success: Boolean,
    @SerialName("avatar_url") val avatarUrl: String
)

@Serializable
data class ErrorResponse(
    val error: String,
    val message: String? = null
)

// ── 文件列表响应 ──────────────────────────────────────────

@Serializable
data class RoomFilesListResponse(
    @SerialName("room_id") val roomId: String,
    val count: String,
    val files: List<RoomFileItemResponse>
)

@Serializable
data class RoomFileItemResponse(
    @SerialName("file_id") val fileId: String,
    @SerialName("file_name") val fileName: String,
    @SerialName("file_size") val fileSize: String,
    @SerialName("mime_type") val mimeType: String,
    @SerialName("uploader_id") val uploaderId: String,
    @SerialName("uploaded_at") val uploadedAt: String
)

// ── 文件上传响应 ──────────────────────────────────────────

@Serializable
data class FileUploadSuccessResponse(
    @SerialName("file_id") val fileId: String,
    @SerialName("file_name") val fileName: String,
    @SerialName("file_size") val fileSize: String,
    val status: String = "uploaded"
)
