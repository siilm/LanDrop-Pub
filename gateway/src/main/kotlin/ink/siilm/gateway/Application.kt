package ink.siilm.gateway

import ink.siilm.core.CoreModule
import ink.siilm.gateway.auth.jwtAuth
import ink.siilm.gateway.auth.JwtConfig
import ink.siilm.gateway.auth.JwtValidator
import ink.siilm.gateway.cli.CliHandlers
import ink.siilm.gateway.cli.CliService
import ink.siilm.gateway.codec.JsonCodec
import ink.siilm.gateway.file.FileChannelOps
import ink.siilm.gateway.http.HttpApiRoutes
import ink.siilm.gateway.ws.WebSocketFrameHandler
import ink.siilm.gateway.ws.WebSocketSessionManager
import ink.siilm.proto.InternalComm.Envelope
import ink.siilm.proto.InternalComm.SessionEvent
import ink.siilm.shared.bridge.CoreBridge
import ink.siilm.shared.config.ServerConfig
import ink.siilm.shared.util.CoroutineUtil
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.util.*
import kotlin.time.Duration.Companion.milliseconds

fun main() {
    // 若运行目录下无 landrop.properties，生成样板文件并退出
    val propFile = java.io.File("landrop.properties")
    if (!propFile.exists()) {
        val sample = Application::class.java.classLoader.getResourceAsStream("landrop.properties.example")
        if (sample != null) {
            propFile.outputStream().use { out -> sample.copyTo(out) }
            println("已生成样板配置文件 landrop.properties，请编辑后重新启动。")
        } else {
            println("错误：未找到内建样板配置，无法生成 landrop.properties")
        }
        return
    }

    val config = ServerConfig()
    val appScope = CoroutineUtil.createAppScope("landrop")

    val bridge = CoreBridge(appScope)
    val coreModule = CoreModule(bridge, appScope)
    val coreEngine = coreModule.createEngine()

    appScope.launch {
        coreEngine.start().join()
    }

    val jwtConfig = JwtConfig()
    val jwtValidator = JwtValidator(jwtConfig)

    val sessionManager = WebSocketSessionManager(bridge, coreModule.roomManager)
    val jsonCodec = JsonCodec()
    val frameHandler = WebSocketFrameHandler(bridge, sessionManager, jsonCodec, coreModule.chatHistoryService, coreModule.permissionService, coreModule.roomManager, coreModule.roomJoinService, coreModule.eventService)
    val fileChannelOps = FileChannelOps(config)
    val httpRoutes = HttpApiRoutes(config, sessionManager, fileChannelOps, jsonCodec, bridge,
        coreModule.authService, coreModule.roomManager, coreModule.roomJoinService,
        coreModule.permissionService, coreModule.chatHistoryService, coreModule.serverConfigManager, jwtValidator,
        coreModule.storageAllocator, coreModule.eventService)

    fun authenticate(session: WebSocketServerSession): Pair<String, ink.siilm.gateway.auth.JwtClaims?>? {
        val token = session.call.request.queryParameters["token"]
        if (!token.isNullOrBlank()) {
            val claims = jwtValidator.validate(token)
            if (claims != null && !claims.isExpired) {
                log.info("JWT auth success: userId={}, sessionId={}", claims.userId, claims.sessionId)
                return Pair(claims.userId, claims)
            }
            log.warn("JWT auth failed: token invalid or expired")
            return null
        }
        log.warn("Authentication failed: no token provided")
        return null
    }

    embeddedServer(Netty, port = config.port, host = config.host) {
        install(WebSockets) {
            pingPeriodMillis = 0L  // 禁用 Ktor 协议级 ping，由应用层心跳接管
            timeoutMillis = config.readTimeoutMs + 30_000L  // 兜底超时（长于应用 readTimeout）
            maxFrameSize = Long.MAX_VALUE
            masking = false
        }

        // CORS 处理（分两层）：
        // 1. Monitoring 层（先于 Authentication）：白名单校验 + 基础 CORS 响应头
        // 2. Routing 层：OPTIONS 预检 catch-all（在 authenticate 之前拦截，避免 405）
        // Ktor 3.x 的 RouteScopedPlugin CORS 运行在路由阶段，晚于 Authentication，无法使用。
        // 白名单由配置文件 cors.allowed_origins 控制（逗号分隔），空列表 = 禁用 CORS。
        intercept(ApplicationCallPipeline.Monitoring) {
            val corsLog = LoggerFactory.getLogger("CORS")
            val allowedOrigins = config.corsAllowedOrigins

            val origin = call.request.headers.getAll("Origin")?.singleOrNull() ?: return@intercept
            val method = call.request.local.method
            val path = call.request.local.uri
            corsLog.info("{} {} origin={}", method.value, path, origin)

            // /api/health 为公开端点，放通所有 Origin
            if (path == "/api/health") {
                call.response.headers.append("Access-Control-Allow-Origin", origin)
                call.response.headers.append("Access-Control-Allow-Credentials", "true")
                return@intercept
            }

            // 鉴权 API — 需要白名单
            if (allowedOrigins.isEmpty()) {
                return@intercept // 未配置白名单 = 禁用 CORS
            }

            if (origin.lowercase() !in allowedOrigins) {
                corsLog.warn("CORS denied: origin {} not in allowed list", origin)
                call.response.headers.append("Access-Control-Allow-Origin", allowedOrigins.first())
                call.respondText(
                    """{"error":"CORS origin not allowed"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.Forbidden
                )
                return@intercept
            }

            // 合法 Origin — 添加基础 CORS 响应头
            call.response.headers.append("Access-Control-Allow-Origin", origin)
            call.response.headers.append("Access-Control-Allow-Credentials", "true")
        }

        install(Authentication) {
            jwtAuth(jwtValidator)
        }
        install(ContentNegotiation) {
            json()
        }

        routing {
            // CORS 预检 catch-all：在所有路由之前拦截 OPTIONS，避免路由引擎返回 405
            // 基础 CORS 头（Allow-Origin, Allow-Credentials）已由 Monitoring 拦截器添加，
            // 此处仅补充预检响应所需的 Method/Headers 声明并返回 200。
            options("{...}") {
                if (call.response.headers["Access-Control-Allow-Origin"] == null) {
                    // Monitoring 拦截器未处理（白名单为空或非同源请求），放行让路由正常处理
                    return@options
                }
                val origin = call.request.headers.getAll("Origin")?.singleOrNull()
                if (origin == null) {
                    return@options
                }
                call.response.headers.append(
                    "Access-Control-Allow-Methods",
                    "GET, POST, PUT, DELETE, PATCH, OPTIONS"
                )
                val requestHeaders = call.request.headers.getAll("Access-Control-Request-Headers")
                    ?.singleOrNull()
                val allowed = if (requestHeaders.isNullOrBlank()) {
                    "Content-Type, Authorization, X-Requested-With"
                } else {
                    "Content-Type, Authorization, $requestHeaders"
                }
                call.response.headers.append("Access-Control-Allow-Headers", allowed)
                call.response.headers.append("Access-Control-Max-Age", "3600")
                call.respond(HttpStatusCode.OK)
            }

            with(httpRoutes) { configureRouting() }
            webSocket("/ws") {
                val authResult = authenticate(this)
                if (authResult == null) {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Authentication required"))
                    return@webSocket
                }
                val (userId, jwtClaims) = authResult
                val sessionId = jwtClaims?.sessionId ?: UUID.randomUUID().toString()

                log.info("WebSocket connected: userId={}, sessionId={}", userId, sessionId)

                sessionManager.register(userId, sessionId, this)

                val connectedEvent = Envelope.newBuilder()
                    .setMessageId(generateMessageId())
                    .setTimestamp(System.currentTimeMillis())
                    .setSessionId(sessionId)
                    .setSessionEvent(
                        SessionEvent.newBuilder()
                            .setUserId(userId)
                            .setType(SessionEvent.Type.CONNECTED)
                    )
                    .build()
                bridge.sendToCore(connectedEvent)

                val heartbeatIntervalMs = config.heartbeatIntervalMs
                val maxLost = config.maxLostPingCount
                val readTimeoutMs = config.readTimeoutMs

                @OptIn(DelicateCoroutinesApi::class)
                val inboundJob = GlobalScope.launch(CoroutineName("ws-inbound-${'$'}userId")) {
                    try {
                        for (frame in incoming) {
                            sessionManager.markActivity(userId)
                            if (frame is Frame.Text) {
                                val text = frame.readText()
                                log.debug("<<< [WS] recv frame userId={}: {}", userId, text.take(200))
                                frameHandler.handleIncoming(userId, text)
                            } else {
                                log.debug("<<< [WS] recv non-text frame userId={}: {}", userId, frame)
                            }
                        }
                    } catch (_: CancellationException) {
                    } catch (e: Exception) {
                        log.warn("Inbound error for userId={}", userId, e)
                    }
                }

                @OptIn(DelicateCoroutinesApi::class)
                val outboundJob = GlobalScope.launch(CoroutineName("ws-outbound-${'$'}userId")) {
                    try {
                        bridge.receiveFromCore().collect { envelope ->
                            frameHandler.handleOutgoing(envelope)
                        }
                    } catch (_: CancellationException) {
                    } catch (e: Exception) {
                        log.warn("Outbound error for userId={}", userId, e)
                    }
                }

                @OptIn(DelicateCoroutinesApi::class)
                val heartbeatJob = GlobalScope.launch(CoroutineName("ws-heartbeat-${'$'}userId")) {
                    try {
                        while (isActive) {
                            delay(heartbeatIntervalMs.milliseconds)
                            if (!sessionManager.isConnected(userId)) break
                            if (sessionManager.incrementPingAndCheck(userId, maxLost)) {
                                log.warn("Heartbeat lost for userId={}, pendingPingCount>{}, closing (code=4001)", userId, maxLost)
                                val session = sessionManager.getByUserId(userId)
                                session?.webSocketSession?.close(CloseReason(4001, "Heartbeat lost"))
                                break
                            }
                            val msg = """{"type":"ping","timestamp":${System.currentTimeMillis()}}"""
                            log.debug(">>> [WS] send ping userId={}: {}", userId, msg)
                            sessionManager.sendToUser(userId, msg)
                        }
                    } catch (_: CancellationException) {
                    }
                }

                @OptIn(DelicateCoroutinesApi::class)
                val readTimeoutJob = GlobalScope.launch(CoroutineName("ws-readtimeout-${'$'}userId")) {
                    try {
                        while (isActive) {
                            delay(5_000.milliseconds)
                            val session = sessionManager.getByUserId(userId) ?: break
                            val idleMs = java.time.Duration.between(session.lastActivity, java.time.Instant.now()).toMillis()
                            if (idleMs > readTimeoutMs) {
                                log.warn("Read timeout for userId={}, idleMs={}, closing (code=4002)", userId, idleMs)
                                session.webSocketSession.close(CloseReason(4002, "Read timeout"))
                                break
                            }
                        }
                    } catch (_: CancellationException) {
                    }
                }

                // 等待入站消费者自然结束（incoming channel 关闭时 for 循环退出）
                inboundJob.join()
                outboundJob.cancel()
                heartbeatJob.cancel()
                readTimeoutJob.cancel()

                log.info("WebSocket disconnected: userId={}, reason=(incoming stream ended)", userId)

                val disconnectedEvent = Envelope.newBuilder()
                    .setMessageId(generateMessageId())
                    .setTimestamp(System.currentTimeMillis())
                    .setSessionId(sessionId)
                    .setSessionEvent(
                        SessionEvent.newBuilder()
                            .setUserId(userId)
                            .setType(SessionEvent.Type.DISCONNECTED)
                    )
                    .build()
                bridge.sendToCore(disconnectedEvent)

                sessionManager.unregister(userId)
            }
        }
    }.also {
        println("========================================")
        println("  LanDrop Server started")
        println("  Listening on http://${config.host}:${config.port}")
        println("========================================")
    }.start(wait = false)

    // CLI 交互模式（仅在真实终端下运行，后台/守护进程自动跳过）
    if (System.console() != null) {
        val cliHandlers = CliHandlers(coreModule.authService, coreModule.roomManager, sessionManager)
        val cliService = CliService(cliHandlers, CoroutineScope(Dispatchers.IO))
        cliService.startInteractive()
        // 阻塞 main 直到 shutdown
        while (true) { Thread.sleep(1000) }
    } else {
        log.info("No interactive console detected, skipping CLI mode")
        while (true) { Thread.sleep(1000) }
    }
}

private val log = LoggerFactory.getLogger("ink.siilm.gateway.Application")
private fun generateMessageId() = UUID.randomUUID().toString()