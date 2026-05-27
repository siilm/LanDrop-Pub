package ink.siilm.core.room

import ink.siilm.core.room.JoinResult
import ink.siilm.core.room.InviteResult
import ink.siilm.core.room.ApproveResult
import ink.siilm.core.auth.PermissionService
import ink.siilm.core.maintenance.PublicAdminRoomCache
import ink.siilm.core.persistence.*
import ink.siilm.shared.config.LandropProperties
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RoomManagerTest {
    private lateinit var roomManager: RoomManager
    private lateinit var joinService: RoomJoinService
    private lateinit var permService: PermissionService
    private lateinit var cache: PublicAdminRoomCache

    private val ownerId = "u_owner001"
    private val aliceId = "u_alice001"
    private val bobId = "u_bob001"

    private var userIdCounter = 0

    @BeforeAll
    fun setUp() {
        val h2Config = LandropProperties.DatabaseConfig(
            url = "jdbc:h2:mem:landrop-room-test2;DB_CLOSE_DELAY=-1",
            user = "sa", password = "", driver = "org.h2.Driver",
            maxPoolSize = 5, minIdle = 1, connectionTimeoutMs = 5000
        )
        DatabaseFactory.init(h2Config)
        DatabaseMigrator.migrate()

        roomManager = RoomManager()
        joinService = RoomJoinService(roomManager)
        permService = PermissionService(roomManager)
        cache = PublicAdminRoomCache()

        // 预置测试用户：owner (global_role=owner) + alice + bob
        transaction {
            listOf(
                Triple(ownerId, "owner_admin", "owner"),
                Triple(aliceId, "alice", "member"),
                Triple(bobId, "bob", "member"),
            ).forEach { (uid, name, role) ->
                UserTable.insert {
                    it[UserTable.userId] = uid
                    it[UserTable.username] = name
                    it[UserTable.publicKey] = "TESTKEY"
                    it[UserTable.globalRole] = role
                    it[UserTable.createdAt] = System.currentTimeMillis()
                }
            }
            // 将 ownerId 设为 public_admin（用于缓存测试）
            GlobalRoleTable.insert {
                it[GlobalRoleTable.userId] = ownerId
                it[GlobalRoleTable.role] = "public_admin"
                it[GlobalRoleTable.grantedAt] = System.currentTimeMillis()
            }
        }
    }

    @AfterAll
    fun tearDown() {
        DatabaseMigrator.dropAll()
        DatabaseFactory.shutdown()
    }

    /** 每次创建房间使用独立 creator 避免配额耗尽 */
    private fun newCreatorId(): String = "u_test${++userIdCounter}"

    private fun ensureUser(userId: String, role: String = "member") {
        transaction {
            val exists = UserTable.selectAll().where { UserTable.userId eq userId }.count()
            if (exists == 0L) {
                UserTable.insert {
                    it[UserTable.userId] = userId
                    it[UserTable.username] = "testuser_$userId"
                    it[UserTable.publicKey] = "TESTKEY"
                    it[UserTable.globalRole] = role
                    it[UserTable.createdAt] = System.currentTimeMillis()
                }
            }
        }
    }

    // ═══════════ B1: 基础 CRUD ═══════════

    @Test
    fun `create room and verify`() {
        val uid = newCreatorId(); ensureUser(uid)
        val result = roomManager.createRoom("TestRoom", uid)
        assertTrue(result.isSuccess)
        val room = result.getOrThrow()
        assertEquals("TestRoom", room.roomName)
        assertEquals(1, room.memberCount)
        assertTrue(room.roomId.length == 6)
        assertTrue(uid in roomManager.getMemberIds(room.roomId))
    }

    @Test
    fun `create room with password`() {
        val uid = newCreatorId(); ensureUser(uid)
        val result = roomManager.createRoom("SecretRoom", uid, RoomManager.ROOM_TYPE_LIMIT_CHAT)
        assertTrue(result.isSuccess)
        val room = result.getOrThrow()
        // 限制聊天房间需审批加入
        assertEquals(JoinResult.Pending::class, joinService.joinRoom(room.roomId, bobId)::class)
    }

    @Test
    fun `dissolve room`() {
        val uid = newCreatorId(); ensureUser(uid)
        val result = roomManager.createRoom("TempRoom", uid)
        val roomId = result.getOrThrow().roomId
        assertTrue(roomManager.dissolveRoom(roomId, uid).isSuccess)
        assertFalse(roomManager.isRoomActive(roomId))
    }

    @Test
    fun `list rooms filters`() {
        val uid = newCreatorId(); ensureUser(uid)
        roomManager.createRoom("PubRoom", uid, roomType = RoomManager.ROOM_TYPE_MEMBER_PUBLIC)
        roomManager.createRoom("PrivRoom", uid, roomType = RoomManager.ROOM_TYPE_MEMBER_PRIVATE)
        val pub = roomManager.listRooms(roomType = RoomManager.ROOM_TYPE_MEMBER_PUBLIC)
        assertTrue(pub.all { roomManager.getRoomType(it.roomId) == RoomManager.ROOM_TYPE_MEMBER_PUBLIC })
        assertTrue(roomManager.listRooms().size >= 2)
    }

    // ═══════════ B2: 加入/邀请/审批 ═══════════

    @Test
    fun `public room auto-join`() {
        val uid = newCreatorId(); ensureUser(uid)
        val result = roomManager.createRoom("PubRoom", uid, roomType = RoomManager.ROOM_TYPE_MEMBER_PUBLIC)
        val roomId = result.getOrThrow().roomId
        assertEquals(JoinResult.Joined, joinService.joinRoom(roomId, bobId))
        assertTrue(bobId in roomManager.getMemberIds(roomId))
    }

    @Test
    fun `private room requires approval`() {
        val uid = newCreatorId(); ensureUser(uid)
        val result = roomManager.createRoom("PrivRoom", uid, roomType = RoomManager.ROOM_TYPE_MEMBER_PRIVATE)
        val roomId = result.getOrThrow().roomId
        val joinResult = joinService.joinRoom(roomId, bobId)
        assertTrue(joinResult is JoinResult.Pending)
        val reqs = joinService.getPendingJoinRequests(roomId)
        assertTrue(reqs.isNotEmpty())
        assertEquals(ApproveResult.Success, joinService.approveJoin(roomId, reqs.first().id, uid))
        assertTrue(bobId in roomManager.getMemberIds(roomId))
    }

    @Test
    fun `invite and approve flow`() {
        val uid = newCreatorId(); ensureUser(uid)
        val result = roomManager.createRoom("InvRoom", uid)
        val roomId = result.getOrThrow().roomId
        // 创建者发起邀请，应创建待审批记录
        val inviteResult = joinService.inviteToRoom(roomId, uid, bobId)
        assertTrue(inviteResult is InviteResult.Success, "Invite should create pending record, got $inviteResult")
        // bob 不应该在房间里（需要受邀人同意+管理员审批后才加入）
        assertTrue(bobId !in roomManager.getMemberIds(roomId))
        // 待审批邀请应该存在
        val invites = joinService.getPendingInvites(roomId)
        assertTrue(invites.any { it.inviteeId == bobId })
    }

    // ═══════════ B3: 成员角色管理 ═══════════

    @Test
    fun `appoint and remove admin`() {
        val uid = newCreatorId(); ensureUser(uid)
        val result = roomManager.createRoom("AdmRoom", uid)
        val roomId = result.getOrThrow().roomId
        roomManager.joinRoom(roomId, bobId)
        assertTrue(roomManager.appointAdmin(roomId, bobId))
        assertEquals(RoomManager.ROLE_ADMIN, roomManager.getMemberRole(roomId, bobId))
        assertTrue(roomManager.removeAdmin(roomId, bobId))
        assertEquals(RoomManager.ROLE_MEMBER, roomManager.getMemberRole(roomId, bobId))
    }

    @Test
    fun `mute and unmute`() {
        val uid = newCreatorId(); ensureUser(uid)
        val result = roomManager.createRoom("MuteRoom", uid)
        val roomId = result.getOrThrow().roomId
        roomManager.joinRoom(roomId, bobId)
        assertTrue(roomManager.muteMember(roomId, bobId))
        assertTrue(roomManager.isMuted(roomId, bobId))
        assertTrue(roomManager.unmuteMember(roomId, bobId))
        assertFalse(roomManager.isMuted(roomId, bobId))
    }

    @Test
    fun `kick member`() {
        val uid = newCreatorId(); ensureUser(uid)
        val result = roomManager.createRoom("KickRoom", uid)
        val roomId = result.getOrThrow().roomId
        roomManager.joinRoom(roomId, bobId)
        roomManager.kickMember(roomId, bobId)
        assertFalse(bobId in roomManager.getMemberIds(roomId))
    }

    @Test
    fun `transfer creater`() {
        val uid = newCreatorId(); ensureUser(uid)
        val result = roomManager.createRoom("XferRoom", uid)
        val roomId = result.getOrThrow().roomId
        roomManager.joinRoom(roomId, bobId)
        assertTrue(roomManager.transferCreater(roomId, bobId))
        assertEquals(bobId, roomManager.getCreatorId(roomId))
    }

    // ═══════════ B4: 权限判定 ═══════════

    @Test
    fun `owner has all permissions`() {
        val uid = newCreatorId(); ensureUser(uid)
        val result = roomManager.createRoom("PermRoom1", uid)
        val roomId = result.getOrThrow().roomId
        assertTrue(permService.canDissolveRoom(ownerId, roomId))
        assertTrue(permService.canManageAdmin(ownerId, roomId))
        assertTrue(permService.canManageMember(ownerId, roomId))
    }

    @Test
    fun `creater has room permissions`() {
        val uid = newCreatorId(); ensureUser(uid)
        val result = roomManager.createRoom("PermRoom2", uid)
        val roomId = result.getOrThrow().roomId
        assertTrue(permService.canDissolveRoom(uid, roomId))
        assertTrue(permService.canManageAdmin(uid, roomId))
        assertTrue(permService.canManageMember(uid, roomId))
    }

    @Test
    fun `regular member lacks management permissions`() {
        val uid = newCreatorId(); ensureUser(uid)
        val result = roomManager.createRoom("PermRoom3", uid)
        val roomId = result.getOrThrow().roomId
        roomManager.joinRoom(roomId, bobId)
        assertFalse(permService.canDissolveRoom(bobId, roomId))
        assertFalse(permService.canManageAdmin(bobId, roomId))
        assertFalse(permService.canManageMember(bobId, roomId))
    }

    @Test
    fun `admin can manage members but not appoint admins`() {
        val uid = newCreatorId(); ensureUser(uid)
        val result = roomManager.createRoom("PermRoom4", uid)
        val roomId = result.getOrThrow().roomId
        roomManager.joinRoom(roomId, bobId)
        roomManager.appointAdmin(roomId, bobId)
        assertTrue(permService.canManageMember(bobId, roomId))
        assertFalse(permService.canManageAdmin(bobId, roomId))
        assertFalse(permService.canDissolveRoom(bobId, roomId))
    }

    // ═══════════ B5: PublicAdmin 缓存 ═══════════

    @Test
    fun `cache populated after full refresh`() {
        val uid = newCreatorId(); ensureUser(uid)
        roomManager.createRoom("CacheRoom", uid, roomType = RoomManager.ROOM_TYPE_MEMBER_PUBLIC)
        cache.fullRefresh()
        val managed = cache.getManagedRoomIds()
        // 至少有一个 public_admin 用户 (ownerId)，所以缓存应非空
        assertTrue(managed.isNotEmpty(), "Cache should be non-empty after refresh")
    }

    @Test
    fun `room created by member appears in cache`() {
        val uid = newCreatorId(); ensureUser(uid)
        val result = roomManager.createRoom("MemberRoom", uid, roomType = RoomManager.ROOM_TYPE_MEMBER_PRIVATE)
        val roomId = result.getOrThrow().roomId
        cache.onRoomCreated(roomId, RoomManager.ROOM_TYPE_MEMBER_PRIVATE)
        assertTrue(roomId in cache.getManagedRoomIds())
    }
}
