package ink.siilm.core.persistence

import ink.siilm.shared.config.LandropProperties
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

object DatabaseMigrator {
    fun migrate() {
        log.info("Running database migration...")
        log.info("It will take a long time...")
        transaction {
            SchemaUtils.createMissingTablesAndColumns(
                UserTable, UserSessionTable, TrustDeviceTable, GlobalRoleTable,
                RoomTable, RoomMemberTable, RoomInviteTable, RoomJoinRequestTable,
                ChatMessageTable, FileTable, RoomFileTable, PublicManagedRoomTable, ServerConfigTable,
                EventTable
            )
            ensureDefaultConfig()
        }
        log.info("Database migration completed")
    }

    fun dropAll() {
        log.warn("Dropping all tables...")
        transaction {
            SchemaUtils.drop(ServerConfigTable, PublicManagedRoomTable, FileTable, ChatMessageTable,
                RoomJoinRequestTable, RoomInviteTable, RoomMemberTable, RoomTable,
                GlobalRoleTable, TrustDeviceTable, UserSessionTable, UserTable)
        }
        log.info("All tables dropped")
    }

    private fun ensureDefaultConfig() {
        val existingKeys = ServerConfigTable.selectAll().map { it[ServerConfigTable.key] }.toSet()
        val defaults = mapOf(
            "max_rooms_per_member" to LandropProperties.getConfigMaxRoomsPerMember().toString(),
            "allow_member_create_rooms" to LandropProperties.getConfigAllowMemberCreateRooms().toString(),
        )
        for ((key, value) in defaults) {
            if (key !in existingKeys) {
                ServerConfigTable.insert { it[ServerConfigTable.key] = key; it[ServerConfigTable.value] = value }
                log.info("Inserted default config: {} = {}", key, value)
            }
        }
    }

    private val log = LoggerFactory.getLogger(DatabaseMigrator::class.java)
}
