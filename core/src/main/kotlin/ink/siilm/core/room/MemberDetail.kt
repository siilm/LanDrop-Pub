package ink.siilm.core.room

/**
 * 房间成员详情（core 内部使用，与 gateway 的 Responses.MemberDetail 不同）。
 */
data class MemberDetail(
    val userId: String,
    val displayName: String,
    val username: String = "",
    val role: Int,
    val avatarUrl: String = "",
    val muted: Int = 0
)
