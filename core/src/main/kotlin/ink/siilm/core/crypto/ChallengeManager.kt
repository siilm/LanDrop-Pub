package ink.siilm.core.crypto

import ink.siilm.shared.config.LandropProperties
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 挑战值管理器。登录步骤1时生成随机挑战值，TTL 从配置文件读取。
 */
class ChallengeManager(private val ttlSeconds: Long = LandropProperties.getChallengeTtlSeconds()) {
    private val random = SecureRandom()
    private val challenges = ConcurrentHashMap<String, ChallengeEntry>()

    private val cleanupExecutor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "challenge-cleanup").apply { isDaemon = true }
    }

    init {
        cleanupExecutor.scheduleWithFixedDelay({ purgeExpired() }, 30, 30, TimeUnit.SECONDS)
    }

    fun generate(userId: String): Pair<String, String> {
        val tempSessionId = UUID.randomUUID().toString()
        val randomBytes = ByteArray(32).also { random.nextBytes(it) }
        val timestamp = System.currentTimeMillis()
        val challengeBytes = randomBytes + timestamp.toString().toByteArray() + tempSessionId.toByteArray()
        val entry = ChallengeEntry(challengeBytes, userId, System.currentTimeMillis())
        challenges[tempSessionId] = entry
        val challengeBase64 = Base64.getEncoder().encodeToString(challengeBytes)
        return Pair(tempSessionId, challengeBase64)
    }

    fun take(tempSessionId: String): ChallengeEntry? {
        val entry = challenges.remove(tempSessionId) ?: return null
        val age = System.currentTimeMillis() - entry.createdAt
        if (age > ttlSeconds * 1000) { log.warn("Challenge expired for {}", tempSessionId); return null }
        return entry
    }

    fun purgeExpired() {
        val now = System.currentTimeMillis()
        val threshold = ttlSeconds * 1000
        challenges.entries.removeIf { now - it.value.createdAt > threshold }
    }

    data class ChallengeEntry(val challenge: ByteArray, val userId: String, val createdAt: Long) {
        override fun equals(other: Any?): Boolean = other is ChallengeEntry && userId == other.userId && challenge.contentEquals(other.challenge)
        override fun hashCode(): Int = userId.hashCode() * 31 + challenge.contentHashCode()
    }

    companion object {
        private val log = LoggerFactory.getLogger(ChallengeManager::class.java)
    }
}
