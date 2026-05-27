package ink.siilm.core.config

import ink.siilm.core.persistence.ServerConfigTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

class ServerConfigManager {
    fun get(key: String): String? = transaction {
        ServerConfigTable.selectAll().where { ServerConfigTable.key eq key }.singleOrNull()?.get(ServerConfigTable.value)
    }
    fun getAll(): Map<String, String> = transaction {
        ServerConfigTable.selectAll().associate { it[ServerConfigTable.key] to it[ServerConfigTable.value] }
    }
    fun set(key: String, value: String) {
        transaction {
            val exists = ServerConfigTable.selectAll().where { ServerConfigTable.key eq key }.count()
            if (exists > 0L) ServerConfigTable.update({ ServerConfigTable.key eq key }) { it[ServerConfigTable.value] = value }
            else ServerConfigTable.insert { it[ServerConfigTable.key] = key; it[ServerConfigTable.value] = value }
        }
        log.info("Config set: {} = {}", key, value)
    }
    fun delete(key: String) { transaction { ServerConfigTable.deleteWhere { ServerConfigTable.key eq key } } }
    fun getInt(key: String, default: Int = 0): Int = get(key)?.toIntOrNull() ?: default
    fun getBoolean(key: String, default: Boolean = false): Boolean = get(key)?.toBooleanStrictOrNull() ?: default

    companion object { private val log = LoggerFactory.getLogger(ServerConfigManager::class.java) }
}
