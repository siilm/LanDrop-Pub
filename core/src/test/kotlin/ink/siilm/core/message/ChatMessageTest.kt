package ink.siilm.core.message

import ink.siilm.core.user.UserManager
import ink.siilm.core.room.RoomManager
import ink.siilm.core.persistence.*
import ink.siilm.shared.bridge.CoreBridge
import ink.siilm.shared.config.LandropProperties
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
class ChatMessageTest {
    private lateinit var chatHistory: ChatHistoryService
    private lateinit var userManager: UserManager
    private lateinit var roomManager: RoomManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val bridge = CoreBridge(scope)

    private val aliceId = "u_alice"
    private val bobId = "u_bob"

    private fun textElements(text: String): String {
        val escaped = text.replace("\\", "\\\\").replace("\"", "\\\"")
        return "[{\"type\":\"text\",\"content\":\"$escaped\"}]"
    }

    @BeforeAll
    fun setUp() {
        val h2Config = LandropProperties.DatabaseConfig(
            url = "jdbc:h2:mem:landrop-chat-test;DB_CLOSE_DELAY=-1",
            user = "sa", password = "", driver = "org.h2.Driver",
            maxPoolSize = 5, minIdle = 1, connectionTimeoutMs = 5000
        )
        DatabaseFactory.init(h2Config)
        DatabaseMigrator.migrate()

        chatHistory = ChatHistoryService()
        userManager = UserManager()
        roomManager = RoomManager()

        transaction {
            listOf(aliceId to "alice", bobId to "bob").forEach { (uid, name) ->
                UserTable.insert {
                    it[UserTable.userId] = uid
                    it[UserTable.username] = name
                    it[UserTable.publicKey] = "KEY"
                    it[UserTable.createdAt] = System.currentTimeMillis()
                }
            }
        }
        roomManager.createRoom("TestRoom", aliceId, roomType = RoomManager.ROOM_TYPE_MEMBER_PUBLIC)
    }

    @AfterAll
    fun tearDown() {
        DatabaseMigrator.dropAll()
        DatabaseFactory.shutdown()
    }

    @Volatile private var roomCounter = 0
    private fun newRoomId(): String {
        val creatorId = "u_creator_${++roomCounter}"
        transaction {
            val exists = UserTable.selectAll().where { UserTable.userId eq creatorId }.count()
            if (exists == 0L) {
                UserTable.insert {
                    it[UserTable.userId] = creatorId
                    it[UserTable.username] = "creator_$roomCounter"
                    it[UserTable.publicKey] = "KEY"
                    it[UserTable.createdAt] = System.currentTimeMillis()
                }
            }
        }
        val r = roomManager.createRoom("Room$roomCounter", creatorId, roomType = RoomManager.ROOM_TYPE_MEMBER_PUBLIC)
        return r.getOrThrow().roomId
    }

    private fun insertMsg(id: String, from: String, to: String?, room: String?, text: String, ts: Long) {
        transaction {
            ChatMessageTable.insert {
                it[ChatMessageTable.messageId] = id
                it[ChatMessageTable.fromUserId] = from
                if (to != null) it[ChatMessageTable.toUserId] = to
                if (room != null) it[ChatMessageTable.roomId] = room
                it[ChatMessageTable.elements] = textElements(text)
                it[ChatMessageTable.deliveryRequired] = 0
                it[ChatMessageTable.createdAt] = ts
                it[ChatMessageTable.status] = "sent"
            }
        }
    }

    @Test
    fun `persist and query room messages`() {
        val rid = newRoomId()
        insertMsg("msg_1", aliceId, null, rid, "Hello", 1000)
        insertMsg("msg_2", bobId, null, rid, "Hi", 2000)
        val msgs = chatHistory.getRoomMessages(rid)
        assertEquals(2, msgs.size)
        assertTrue(msgs[0].elements.contains("Hello"))
        assertTrue(msgs[1].elements.contains("Hi"))
    }

    @Test
    fun `paginate room messages`() {
        val rid = newRoomId()
        transaction {
            (1..5).forEach { i ->
                insertMsg("page_$i", aliceId, null, rid, "msg$i", i.toLong() * 1000)
            }
        }
        val page = chatHistory.getRoomMessages(rid, before = 4000, limit = 2)
        assertEquals(2, page.size)
        assertTrue(page.all { it.createdAt < 4000 })
    }

    @Test
    fun `query direct messages`() {
        insertMsg("dm_1", aliceId, bobId, null, "Private", 1000)
        insertMsg("dm_2", bobId, aliceId, null, "Reply", 2000)
        val msgs = chatHistory.getDirectMessages(aliceId, bobId)
        assertEquals(2, msgs.size)
    }

    @Test
    fun `delete own message`() {
        val rid = newRoomId()
        insertMsg("del_1", aliceId, null, rid, "ToDelete", 1000)
        assertTrue(chatHistory.deleteMessage("del_1", aliceId))
    }

    @Test
    fun `cannot delete others message`() {
        val rid = newRoomId()
        insertMsg("del_2", aliceId, null, rid, "AliceMsg", 1000)
        assertFalse(chatHistory.deleteMessage("del_2", bobId))
    }

    @Test
    fun `multi device sessions`() {
        userManager.onConnected("s1", aliceId, "alice", "member", "dev1")
        userManager.onConnected("s2", aliceId, "alice", "member", "dev2")
        assertEquals(listOf("s1", "s2").sorted(), userManager.getSessionIds(aliceId).sorted())
        assertTrue(userManager.isOnline(aliceId))
        userManager.onDisconnected("s1")
        assertTrue(userManager.isOnline(aliceId))
        userManager.onDisconnected("s2")
        assertFalse(userManager.isOnline(aliceId))
    }

    @Test
    fun `kicked session removed`() {
        userManager.onConnected("s_kick", bobId, "bob", "member", "dev")
        assertTrue(userManager.isOnline(bobId))
        userManager.onKicked("s_kick")
        assertFalse(userManager.isOnline(bobId))
    }
}
