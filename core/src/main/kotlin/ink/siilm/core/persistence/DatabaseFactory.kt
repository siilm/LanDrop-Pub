package ink.siilm.core.persistence

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import ink.siilm.shared.config.LandropProperties
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.DatabaseConfig
import org.slf4j.LoggerFactory
import javax.sql.DataSource

/**
 * 数据库连接工厂。
 *
 * 通过外部配置文件 landrop.properties 读取连接信息，
 * 支持 MySQL（生产）/ H2（开发测试）/ SQLite（单文件）三种后端。
 *
 * 使用 HikariCP 连接池管理连接。
 */
object DatabaseFactory {
    private var dataSource: HikariDataSource? = null

    /**
     * 初始化数据库连接池。
     * 应在应用启动时调用一次。
     */
    fun init(config: LandropProperties.DatabaseConfig = LandropProperties.getDatabaseConfig()): Database {
        if (dataSource != null) {
            log.warn("DatabaseFactory already initialized, skipping")
            return connectExisting()
        }

        log.info("Initializing database: driver={}, url={}", config.driver, config.url)

        val hikariConfig = HikariConfig().apply {
            jdbcUrl = config.url
            username = config.user.ifBlank { null }
            password = config.password.ifBlank { null }
            driverClassName = config.driver
            maximumPoolSize = config.maxPoolSize
            minimumIdle = config.minIdle
            connectionTimeout = config.connectionTimeoutMs
            // MySQL 连接优化
            if (config.driver.contains("mysql")) {
                addDataSourceProperty("cachePrepStmts", "true")
                addDataSourceProperty("prepStmtCacheSize", "250")
                addDataSourceProperty("prepStmtCacheSqlLimit", "2048")
            }
        }

        dataSource = HikariDataSource(hikariConfig)

        val database = Database.connect(dataSource as DataSource, databaseConfig = DatabaseConfig { useNestedTransactions = false })

        log.info("Database connection established successfully")
        return database
    }

    private fun connectExisting(): Database {
        return Database.connect(dataSource as DataSource, databaseConfig = DatabaseConfig { useNestedTransactions = false })
    }

    /**
     * 关闭连接池。
     */
    fun shutdown() {
        dataSource?.close()
        dataSource = null
        log.info("Database connection pool shut down")
    }

    private val log = LoggerFactory.getLogger(DatabaseFactory::class.java)
}
