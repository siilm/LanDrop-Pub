package ink.siilm.core.room

/**
 * 加入申请信息（从 RoomJoinService 提取为独立顶层类，避免嵌套类加载问题）。
 */
data class JoinRequestInfo(
    val id: Int,
    val roomId: String,
    val applicantId: String,
    val message: String?,
    val appliedAt: Long,
    val expiresAt: Long? = null
)

/**
 * 邀请信息（从 RoomJoinService 提取为独立顶层类）。
 */
data class InviteInfo(
    val id: Int,
    val roomId: String,
    val inviterId: String,
    val inviteeId: String,
    val requestedAt: Long,
    val expiresAt: Long? = null
)
