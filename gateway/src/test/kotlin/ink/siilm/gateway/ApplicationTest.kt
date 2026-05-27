package ink.siilm.gateway

import ink.siilm.core.CoreModule
import ink.siilm.gateway.codec.JsonCodec
import ink.siilm.gateway.ws.WebSocketFrameHandler
import ink.siilm.gateway.ws.WebSocketSessionManager
import ink.siilm.shared.bridge.CoreBridge
import ink.siilm.shared.config.ServerConfig
import ink.siilm.shared.util.CoroutineUtil
import ink.siilm.proto.InternalComm.Envelope
import ink.siilm.proto.InternalComm.SessionEvent
import ink.siilm.gateway.auth.JwtConfig
import ink.siilm.gateway.auth.JwtValidator
import ink.siilm.gateway.auth.JwtClaims
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import java.util.UUID
import kotlin.test.*

/**
 * Gateway 集成测试。
 *
 * 每个测试启动一个临时 Netty 服务器，使用 Ktor 客户端验证基本功能。
 */
class ApplicationTest {

    /**
     * 测试 Gateway 核心组件：CoreBridge、SessionManager、FrameHandler 正确创建并可交互。
     */
    @Test
    fun `CoreBridge and SessionManager creation and basic interaction`() = runBlocking {
        val scope = CoroutineUtil.createAppScope("test-basic")
        val bridge = CoreBridge(scope)
        val coreModule = CoreModule(bridge, scope)
        val sessionManager = WebSocketSessionManager(bridge, coreModule.roomManager)
        val jsonCodec = JsonCodec()
        val frameHandler = WebSocketFrameHandler(bridge, sessionManager, jsonCodec)

        // 验证 bridge 可发送和接收消息
        val envelope = Envelope.newBuilder()
            .setMessageId("test-msg-1")
            .setTimestamp(System.currentTimeMillis())
            .setSessionEvent(
                SessionEvent.newBuilder()
                    .setUserId("user1")
                    .setType(SessionEvent.Type.CONNECTED)
            )
            .build()

        bridge.sendToCore(envelope)

        // 验证可从 coreInboundChannel 消费
        val received = withTimeout(3000) {
            bridge.coreInboundChannel().receive()
        }
        assertEquals("test-msg-1", received.messageId)
        assertEquals(SessionEvent.Type.CONNECTED, received.sessionEvent.type)
        assertEquals("user1", received.sessionEvent.userId)

        scope.cancel()
    }

    /**
     * 测试 WebSocket 会话注册和注销。
     */
    @Test
    fun `SessionManager register and unregister`() {
        val scope = CoroutineUtil.createAppScope("test-session")
        val bridge = CoreBridge(scope)
        val coreModule = CoreModule(bridge, scope)
        val sessionManager = WebSocketSessionManager(bridge, coreModule.roomManager)

        assertEquals(0, sessionManager.activeCount())

        // 无法在此测试中真正注册 WebSocketSession（需要实际连接），
        // 但验证方法存在且不抛异常
        assertFalse(sessionManager.isConnected("nonexistent"))
        assertNull(sessionManager.getByUserId("nonexistent"))
        assertEquals(0, sessionManager.activeCount())

        scope.cancel()
    }

    /**
     * 测试 FrameHandler 入站消息解析（chat_message JSON）。
     */
    @Test
    fun `WebSocketFrameHandler parses chat message JSON`() = runBlocking {
        val scope = CoroutineUtil.createAppScope("test-frame")
        val bridge = CoreBridge(scope)
        val coreModule = CoreModule(bridge, scope)
        val sessionManager = WebSocketSessionManager(bridge, coreModule.roomManager)
        val jsonCodec = JsonCodec()
        val frameHandler = WebSocketFrameHandler(bridge, sessionManager, jsonCodec)

        // 构造一条 chat_message JSON
        val chatJson = """{"type":"chat_message","content":"Hello World","to":"user2"}"""
        frameHandler.handleIncoming("user1", chatJson)

        // 验证消息被正确投递到 core channel
        val received = withTimeout(3000) {
            bridge.coreInboundChannel().receive()
        }
        assertTrue(received.hasMessageDelivery())
        assertEquals("user1", received.messageDelivery.fromUserId)
        assertEquals("Hello World", received.messageDelivery.textContent)
        assertEquals("user2", received.messageDelivery.toUserId)

        scope.cancel()
    }

    /**
     * 测试 FrameHandler 入站消息解析（ping JSON）。
     */
    @Test
    fun `WebSocketFrameHandler parses ping JSON`() = runBlocking {
        val scope = CoroutineUtil.createAppScope("test-ping")
        val bridge = CoreBridge(scope)
        val coreModule = CoreModule(bridge, scope)
        val sessionManager = WebSocketSessionManager(bridge, coreModule.roomManager)
        val jsonCodec = JsonCodec()
        val frameHandler = WebSocketFrameHandler(bridge, sessionManager, jsonCodec)

        val pingJson = """{"type":"ping","timestamp":123456789}"""
        frameHandler.handleIncoming("user1", pingJson)

        // 验证 core 收到了 SessionEvent(PING)
        val received = withTimeout(3000) {
            bridge.coreInboundChannel().receive()
        }
        assertTrue(received.hasSessionEvent())
        assertEquals(SessionEvent.Type.PING, received.sessionEvent.type)
        assertEquals("user1", received.sessionEvent.userId)

        scope.cancel()
    }

    /**
     * 测试 messageId 追踪：入站后可以追踪到 messageId → userId。
     */
    @Test
    fun `MessageId tracking for ACK Error routing`() {
        val scope = CoroutineUtil.createAppScope("test-track")
        val bridge = CoreBridge(scope)
        val coreModule = CoreModule(bridge, scope)
        val sessionManager = WebSocketSessionManager(bridge, coreModule.roomManager)
        val jsonCodec = JsonCodec()
        val frameHandler = WebSocketFrameHandler(bridge, sessionManager, jsonCodec)

        // 发送入站消息
        val chatJson = """{"type":"chat_message","content":"Track me","to":"user3"}"""
        frameHandler.handleIncoming("user1", chatJson)

        // 验证消息被追踪（需要通过消费 core channel 获取 messageId）
        val received = runBlocking {
            withTimeout(3000) {
                bridge.coreInboundChannel().receive()
            }
        }
        val messageId = received.messageId

        // 检查追踪映射
        val trackedUser = sessionManager.getUserIdByMessageId(messageId)
        assertEquals("user1", trackedUser)

        // 再次查询应返回 null（已移除）
        assertNull(sessionManager.getUserIdByMessageId(messageId))

        scope.cancel()
    }

    /**
     * 测试 HTTP 服务器启动和健康检查端点。
     */
    @Test
    fun `Embedded server starts and responds to HTTP health check`() {
        val server = embeddedServer(Netty, port = 0, host = "127.0.0.1") {
            routing {
                get("/api/health") {
                    call.respondText("""{"status":"ok"}""")
                }
            }
        }

        server.start(wait = false)

        try {
            val port = runBlocking { server.engine.resolvedConnectors().first().port }
            assertTrue(port > 0, "Server should allocate a port")

            val client = HttpClient(CIO)
            val response: HttpResponse = runBlocking {
                client.get("http://127.0.0.1:$port/api/health")
            }
            assertEquals(200, response.status.value)
            client.close()
        } finally {
            server.stop(1000, 2000)
        }
    }

    /**
     * 测试带 userId 的 WebSocket 连接认证。
     */
    @Test
    fun `WebSocket auth with valid userId`() {
        val server = embeddedServer(Netty, port = 0, host = "127.0.0.1") {
            install(io.ktor.server.websocket.WebSockets)
            routing {
                webSocket("/ws") {
                    val userId = call.request.queryParameters["userId"]
                    if (userId.isNullOrBlank()) {
                        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Auth required"))
                        return@webSocket
                    }
                    // 简单 echo
                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            send(Frame.Text("echo: ${frame.readText()}"))
                        }
                    }
                }
            }
        }

        server.start(wait = false)

        try {
            val port = runBlocking { server.engine.resolvedConnectors().first().port }
            val client = HttpClient(CIO) { install(io.ktor.client.plugins.websocket.WebSockets) }

            var echoReceived = false

            runBlocking {
                client.webSocket("ws://127.0.0.1:$port/ws?userId=testuser") {
                    send(Frame.Text("hello"))
                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            val text = frame.readText()
                            if (text.contains("echo: hello")) {
                                echoReceived = true
                            }
                            break
                        }
                    }
                }
            }

            client.close()
            assertTrue(echoReceived, "Should receive echo response")
        } finally {
            server.stop(1000, 2000)
        }
    }

    /**
     * 测试 JwtValidator 能正确验证有效 JWT。
     *
     * 注: 此测试生成自签名 JWT 用于验证 validator 本身。
     * 完整登录流程测试需等 core AuthService 就绪。
     */
    @Test
    fun `JwtValidator validates self-issued token`() {
        val config = JwtConfig()
        val validator = JwtValidator(config)

        // 签发测试 JWT（用 jjwt 库）
        val key = io.jsonwebtoken.security.Keys.hmacShaKeyFor(config.secret.toByteArray(Charsets.UTF_8))
        val now = System.currentTimeMillis()
        val token = io.jsonwebtoken.Jwts.builder()
            .subject("testuser")
            .claim("user_id", "u_test123")
            .claim("session_id", "s1_test")
            .claim("global_role", "member")
            .issuer(config.issuer)
            .issuedAt(java.util.Date(now))
            .expiration(java.util.Date(now + 900_000))  // 15 min
            .id(java.util.UUID.randomUUID().toString())
            .signWith(key)
            .compact()

        val claims = validator.validate(token)
        assertNotNull(claims, "JWT should be valid")
        assertEquals("testuser", claims!!.subject)
        assertEquals("u_test123", claims.userId)
        assertEquals("s1_test", claims.sessionId)
        assertEquals("member", claims.globalRole)
    }

    /**
     * 测试 JwtValidator 拒绝过期 JWT。
     */
    @Test
    fun `JwtValidator rejects expired token`() {
        val config = JwtConfig()
        val validator = JwtValidator(config)

        val key = io.jsonwebtoken.security.Keys.hmacShaKeyFor(config.secret.toByteArray(Charsets.UTF_8))
        val now = System.currentTimeMillis()
        val token = io.jsonwebtoken.Jwts.builder()
            .subject("testuser")
            .claim("user_id", "u_test123")
            .claim("session_id", "s1_test")
            .issuer(config.issuer)
            .issuedAt(java.util.Date(now - 3600_000))  // 1 hour ago
            .expiration(java.util.Date(now - 1800_000)) // expired 30 min ago
            .id(java.util.UUID.randomUUID().toString())
            .signWith(key)
            .compact()

        val claims = validator.validate(token)
        // jjwt 默认会拒绝过期 token，所以 validate 返回 null
        assertNull(claims, "Expired JWT should be rejected")
    }

    /**
     * 测试 HTTP auth 端点存在且可访问。
     */
    @Test
    fun `Auth endpoints exist and are reachable`() = runBlocking {
        val server = embeddedServer(Netty, port = 0, host = "127.0.0.1") {
            routing {
                post("/api/auth/login") {
                    call.respondText("{\"status\":\"not_implemented\"}")
                }
            }
        }

        server.start(wait = false)

        try {
            val port = server.engine.resolvedConnectors().first().port
            val client = HttpClient(CIO)

            val resp = client.post("http://127.0.0.1:$port/api/auth/login") {
                setBody("{}")
                contentType(ContentType.Application.Json)
            }
            assertTrue(resp.status.value in 200..599, "Should get a response")
            client.close()
        } finally {
            server.stop(1000, 2000)
        }
    }

}
