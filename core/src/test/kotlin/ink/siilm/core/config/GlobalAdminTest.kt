package ink.siilm.core.config

import ink.siilm.core.auth.PublicAdminResult
import ink.siilm.core.auth.AuthService
import ink.siilm.core.auth.JwtIssuer
import ink.siilm.core.persistence.*
import ink.siilm.shared.config.LandropProperties
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GlobalAdminTest {
    private lateinit var configMgr: ServerConfigManager
    private lateinit var authService: AuthService
    private val ownerId = "u_owner_admin"
    private val memberId = "u_member_bob"

    @BeforeAll
    fun setUp() {
        val h2Config = LandropProperties.DatabaseConfig(
            url = "jdbc:h2:mem:landrop-admin-test;DB_CLOSE_DELAY=-1",
            user = "sa", password = "", driver = "org.h2.Driver",
            maxPoolSize = 5, minIdle = 1, connectionTimeoutMs = 5000
        )
        DatabaseFactory.init(h2Config)
        DatabaseMigrator.migrate()

        configMgr = ServerConfigManager()
        authService = AuthService(JwtIssuer())

        // 预置 owner 和 member
        transaction {
            listOf(
                Triple(ownerId, "test_owner", "owner"),
                Triple(memberId, "test_member", "member"),
            ).forEach { (uid, name, role) ->
                UserTable.insert {
                    it[UserTable.userId] = uid
                    it[UserTable.username] = name
                    it[UserTable.publicKey] = "KEY"
                    it[UserTable.globalRole] = role
                    it[UserTable.createdAt] = System.currentTimeMillis()
                }
            }
        }
    }

    @AfterAll
    fun tearDown() {
        DatabaseMigrator.dropAll()
        DatabaseFactory.shutdown()
    }

    // ═══════════ D1: ServerConfigManager ═══════════

    @Test
    fun `read preset config`() {
        assertEquals("2", configMgr.get("max_rooms_per_member"))
        assertEquals("true", configMgr.get("allow_member_create_rooms"))
    }

    @Test
    fun `set and get config`() {
        configMgr.set("test_key", "test_value_42")
        assertEquals("test_value_42", configMgr.get("test_key"))
    }

    @Test
    fun `update existing config`() {
        configMgr.set("max_rooms_per_member", "5")
        assertEquals("5", configMgr.get("max_rooms_per_member"))
        configMgr.set("max_rooms_per_member", "2") // restore
    }

    @Test
    fun `getAll returns all configs`() {
        val all = configMgr.getAll()
        assertTrue("max_rooms_per_member" in all)
        assertTrue("allow_member_create_rooms" in all)
    }

    @Test
    fun `delete config`() {
        configMgr.set("temp_key", "temp_val")
        configMgr.delete("temp_key")
        assertNull(configMgr.get("temp_key"))
    }

    @Test
    fun `getInt and getBoolean`() {
        configMgr.set("int_key", "42")
        assertEquals(42, configMgr.getInt("int_key"))
        configMgr.set("bool_key", "true")
        assertTrue(configMgr.getBoolean("bool_key"))
        configMgr.delete("int_key")
        configMgr.delete("bool_key")
    }

    // ═══════════ D2: PublicAdmin 管理 ═══════════

    @Test
    fun `owner appoints public admin`() {
        val result = authService.appointPublicAdmin(ownerId, memberId)
        assertTrue(result is PublicAdminResult.Success)
    }

    @Test
    fun `appointed user shows in list`() {
        authService.appointPublicAdmin(ownerId, memberId)
        val admins = authService.listPublicAdmins()
        assertTrue(admins.any { it.userId == memberId })
    }

    @Test
    fun `non-owner cannot appoint`() {
        val result = authService.appointPublicAdmin(memberId, ownerId)
        assertTrue(result is PublicAdminResult.NotOwner)
    }

    @Test
    fun `owner removes public admin`() {
        authService.appointPublicAdmin(ownerId, memberId)
        val result = authService.removePublicAdmin(ownerId, memberId)
        assertTrue(result is PublicAdminResult.Success)
        val admins = authService.listPublicAdmins()
        assertTrue(admins.none { it.userId == memberId })
    }

    @Test
    fun `non-owner cannot remove`() {
        val result = authService.removePublicAdmin(memberId, ownerId)
        assertTrue(result is PublicAdminResult.NotOwner)
    }

    @Test
    fun `appoint nonexistent user fails`() {
        val result = authService.appointPublicAdmin(ownerId, "u_nonexistent")
        assertTrue(result is PublicAdminResult.UserNotFound)
    }
}
