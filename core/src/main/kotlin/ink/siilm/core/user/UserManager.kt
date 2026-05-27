package ink.siilm.core.user

import java.util.concurrent.ConcurrentHashMap

data class SessionContext(
    val sessionId: String,
    val userId: String,
    val username: String,
    val globalRole: String,
    val deviceId: String?
)

/**
 * 会话与用户管理（C1）。支持多设备同时在线。
 */
class UserManager {
    private val users = ConcurrentHashMap<String, UserState>()
    private val sessions = ConcurrentHashMap<String, SessionContext>()

    fun onConnected(sessionId: String, userId: String, username: String, globalRole: String, deviceId: String?) {
        sessions[sessionId] = SessionContext(sessionId, userId, username, globalRole, deviceId)
        users.compute(userId) { _, existing ->
            (existing ?: UserState(userId, username)).copy(isOnline = true, lastSeen = System.currentTimeMillis())
        }
    }

    fun onDisconnected(sessionId: String) {
        val ctx = sessions.remove(sessionId) ?: return
        val hasOtherSession = sessions.values.any { it.userId == ctx.userId }
        if (!hasOtherSession) {
            users.computeIfPresent(ctx.userId) { _, state ->
                state.copy(isOnline = false, lastSeen = System.currentTimeMillis())
            }
        }
    }

    fun onKicked(sessionId: String) = onDisconnected(sessionId)

    fun isOnline(userId: String): Boolean = users[userId]?.isOnline == true
    fun getSessionIds(userId: String): List<String> = sessions.filter { it.value.userId == userId }.keys.toList()
}

data class UserState(val userId: String, val username: String, val isOnline: Boolean = false, val lastSeen: Long = 0)
