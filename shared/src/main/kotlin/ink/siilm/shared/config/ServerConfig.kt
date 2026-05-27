package ink.siilm.shared.config

/**
 * 服务端配置。当前使用硬编码默认值，未来可从 YAML/TOML 文件加载。
 * 对于 <50 人局域网场景，环境变量或配置文件已足够。
 */
data class ServerConfig(
    val host: String = LandropProperties.getServerHost(),
    val port: Int = LandropProperties.getServerPort(),
    val fileBaseDir: String = "./landrop-files",
    val maxFileSizeMB: Long = 500,
    val maxConcurrentUploads: Int = 10,
    val heartbeatIntervalMs: Long = 20_000,
    val heartbeatTimeoutMs: Long = 30_000,
    val maxLostPingCount: Int = 3,
    val readTimeoutMs: Long = 90_000,
    val fileExpirationHours: Int = 24,
    val maxMessageQueueSize: Int = 256,
)