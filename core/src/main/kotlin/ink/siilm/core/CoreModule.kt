package ink.siilm.core

import ink.siilm.core.auth.AuthService
import ink.siilm.core.crypto.ChallengeManager
import ink.siilm.core.auth.JwtIssuer
import ink.siilm.core.auth.OwnerInitializer
import ink.siilm.core.auth.PermissionService
import ink.siilm.core.persistence.UserTable
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import ink.siilm.core.config.ServerConfigManager
import ink.siilm.core.event.EventService
import ink.siilm.core.file.FileMetaManager
import ink.siilm.core.file.StoragePathAllocator
import ink.siilm.core.maintenance.CleanupJobs
import ink.siilm.core.maintenance.PublicAdminRoomCache
import ink.siilm.core.message.ChatHistoryService
import ink.siilm.core.message.MessageRouter
import ink.siilm.core.persistence.DatabaseFactory
import ink.siilm.core.persistence.DatabaseMigrator
import ink.siilm.core.room.RoomJoinService
import ink.siilm.core.room.RoomManager
import ink.siilm.core.user.UserManager
import ink.siilm.shared.bridge.CoreBridge
import ink.siilm.shared.config.LandropProperties
import kotlinx.coroutines.CoroutineScope
import org.slf4j.LoggerFactory
import java.nio.file.Path

class CoreModule(private val bridge: CoreBridge, private val scope: CoroutineScope) {
    val roomManager: RoomManager by lazy { RoomManager() }
    val jwtIssuer: JwtIssuer by lazy { JwtIssuer() }
    val challengeManager: ChallengeManager by lazy { ChallengeManager() }
    val authService: AuthService by lazy { AuthService(jwtIssuer, challengeManager) }
    val permissionService: PermissionService by lazy { PermissionService(roomManager) }
    val roomJoinService: RoomJoinService by lazy { RoomJoinService(roomManager) }
    val publicAdminRoomCache: PublicAdminRoomCache by lazy { PublicAdminRoomCache() }
    val chatHistoryService: ChatHistoryService by lazy { ChatHistoryService(permissionService, roomManager) }
    val serverConfigManager: ServerConfigManager by lazy { ServerConfigManager() }
    val storageAllocator: StoragePathAllocator by lazy { StoragePathAllocator(Path.of(LandropProperties.getFileBaseDir()), LandropProperties.getStorageMode(), Path.of(LandropProperties.getStorageCloudCacheDir())) }
    val eventService: EventService by lazy { EventService() }

    @Volatile private var dbInitialized = false

    fun initDatabase() {
        if (dbInitialized) { log.warn("Database already initialized"); return }
        DatabaseFactory.init()
        DatabaseMigrator.migrate()
        // Owner 初始化（显式调用，解除 DatabaseMigrator 的跨模块耦合）
        OwnerInitializer.initialize()
        ensurePublicRoom()
        publicAdminRoomCache.fullRefresh()
        dbInitialized = true
        log.info("Core module database initialized")
    }

    fun createEngine(): CoreEngine {
        if (!dbInitialized) initDatabase()
        val userManager = UserManager()
        val fileMetaManager = FileMetaManager(storageAllocator)
        val messageRouter = MessageRouter(bridge, roomManager, userManager, emptyList())
        val coreEngine = CoreEngine(bridge, messageRouter, roomManager, userManager, fileMetaManager, scope)

        // 启动运维清理任务
        CleanupJobs(fileMetaManager, publicAdminRoomCache, chatHistoryService, eventService, scope, roomManager).start()

        return coreEngine
    }

    private fun ensurePublicRoom() {
        val ownerId = transaction {
            UserTable.selectAll().where { UserTable.globalRole eq "owner" }.singleOrNull()?.get(UserTable.userId)
        }
        if (ownerId == null) {
            log.warn("No owner found, skipping Public Room setup")
            return
        }
        val exists = roomManager.getRoom("PUBLIC") != null
        if (!exists) {
            val result = roomManager.createRoomWithId("PUBLIC", "Public Room", ownerId)
            result.fold(
                onSuccess = { log.info("Public Room created with owner={}", ownerId) },
                onFailure = { log.warn("Public Room creation failed: {}", it.message) }
            )
        } else {
            val members = roomManager.getMemberIds("PUBLIC")
            if (ownerId !in members) {
                roomManager.joinRoom("PUBLIC", ownerId)
                log.info("Owner {} added to existing Public Room", ownerId)
            }
        }
    }

    companion object { private val log = LoggerFactory.getLogger(CoreModule::class.java) }
}
