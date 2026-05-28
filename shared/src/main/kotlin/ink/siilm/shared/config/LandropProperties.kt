package ink.siilm.shared.config

import java.io.File
import java.io.FileInputStream
import java.util.Properties

object LandropProperties {
    private val props: Properties by lazy {
        val p = Properties()
        loadDefaults(p)
        val configFile = findConfigFile()
        if (configFile != null) {
            try { FileInputStream(configFile).use { p.load(it) } } catch (_: Exception) {}
        }
        applyEnvOverrides(p)
        p
    }

    private fun findConfigFile(): File? {
        System.getenv("LANDROP_CONFIG")?.let { val f = File(it); if (f.isFile) return f }
        listOf(File("landrop.properties"), File("../landrop.properties")).forEach { if (it.isFile) return it }
        return null
    }

    private fun loadDefaults(p: Properties) {
        p.setProperty("db.url", "jdbc:h2:mem:landrop;DB_CLOSE_DELAY=-1")
        p.setProperty("db.user", "sa"); p.setProperty("db.password", "")
        p.setProperty("db.driver", "org.h2.Driver")
        p.setProperty("db.pool.max_size", "10"); p.setProperty("db.pool.min_idle", "2"); p.setProperty("db.pool.connection_timeout_ms", "30000")
        p.setProperty("jwt.secret", "landrop-dev-secret-change-in-production-32bytes!!")
        p.setProperty("jwt.issuer", "landrop")
        p.setProperty("jwt.access_token_ttl_seconds", "900"); p.setProperty("jwt.refresh_token_ttl_seconds", "1209600")
        p.setProperty("landrop.file.base_dir", "./landrop-files"); p.setProperty("landrop.file.expiration_hours", "24")
        p.setProperty("landrop.challenge.ttl_seconds", "60"); p.setProperty("landrop.room.max_members", "500")
        p.setProperty("landrop.storage.mode", "local"); p.setProperty("landrop.storage.cloud_cache_dir", "./cloud-cache")
        p.setProperty("landrop.config.max_rooms_per_member", "2"); p.setProperty("landrop.config.allow_member_create_rooms", "true")
        p.setProperty("landrop.secrets_dir", "./landrop-files/secrets")
    }

    private fun applyEnvOverrides(p: Properties) {
        System.getenv("LANDROP_DB_URL")?.let { p.setProperty("db.url", it) }
        System.getenv("LANDROP_DB_USER")?.let { p.setProperty("db.user", it) }
        System.getenv("LANDROP_DB_PASSWORD")?.let { p.setProperty("db.password", it) }
        System.getenv("LANDROP_DB_DRIVER")?.let { p.setProperty("db.driver", it) }
        System.getenv("LANDROP_JWT_SECRET")?.let { p.setProperty("jwt.secret", it) }
    }

    fun getString(key: String, default: String = ""): String = props.getProperty(key, default)
    fun getInt(key: String, default: Int = 0): Int = props.getProperty(key)?.toIntOrNull() ?: default
    fun getLong(key: String, default: Long = 0): Long = props.getProperty(key)?.toLongOrNull() ?: default

    data class DatabaseConfig(val url: String, val user: String, val password: String, val driver: String, val maxPoolSize: Int, val minIdle: Int, val connectionTimeoutMs: Long)
    fun getDatabaseConfig() = DatabaseConfig(getString("db.url"), getString("db.user"), getString("db.password"), getString("db.driver"), getInt("db.pool.max_size", 10), getInt("db.pool.min_idle", 2), getLong("db.pool.connection_timeout_ms", 30000))

    data class JwtProperties(val secret: String, val issuer: String, val accessTokenTtlSeconds: Long, val refreshTokenTtlSeconds: Long)
    fun getJwtProperties() = JwtProperties(getString("jwt.secret"), getString("jwt.issuer"), getLong("jwt.access_token_ttl_seconds", 900), getLong("jwt.refresh_token_ttl_seconds", 1209600))

    fun getFileBaseDir() = getString("landrop.file.base_dir", "./landrop-files")
    fun getFileExpirationHours() = getInt("landrop.file.expiration_hours", 168)
    fun getStorageMode() = getString("landrop.storage.mode", "local")
    fun getStorageCloudCacheDir() = getString("landrop.storage.cloud_cache_dir", "./cloud-cache")
    fun getChallengeTtlSeconds() = getLong("landrop.challenge.ttl_seconds", 60)
    fun getRoomMaxMembers() = getInt("landrop.room.max_members", 500)
    fun getConfigMaxRoomsPerMember() = getInt("landrop.config.max_rooms_per_member", 2)
    fun getConfigAllowMemberCreateRooms() = getString("landrop.config.allow_member_create_rooms", "true").toBoolean()
    fun getSecretsDir() = getString("landrop.secrets_dir", "./landrop-files/secrets")
    fun getServerHost() = getString("server.host", "0.0.0.0")
    fun getServerPort() = getInt("server.port", 8080)

    /**
     * CORS 允许的 Origin 白名单（逗号分隔）。
     * 例：cors.allowed_origins=http://localhost:3000,http://192.168.1.100:3000
     * 空字符串表示禁用 CORS（不添加任何 CORS 响应头）。
     */
    fun getCorsAllowedOrigins(): List<String> {
        val raw = getString("cors.allowed_origins", "")
        if (raw.isBlank()) return emptyList()
        return raw.split(",").map { it.trim().lowercase() }.filter { it.isNotBlank() }
    }
}
