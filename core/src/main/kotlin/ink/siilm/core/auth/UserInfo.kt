package ink.siilm.core.auth

data class UserInfo(
    val userId: String,
    val username: String,
    val displayName: String,
    val globalRole: String,
    val isActive: Boolean
)
