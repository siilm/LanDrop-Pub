package ink.siilm.gateway

import ink.siilm.core.auth.AuthService
import ink.siilm.core.auth.PermissionService
import ink.siilm.core.auth.JwtIssuer
import ink.siilm.core.config.ServerConfigManager
import ink.siilm.core.crypto.ChallengeManager
import ink.siilm.core.message.ChatHistoryService
import ink.siilm.core.room.RoomJoinService
import ink.siilm.core.room.RoomManager
import ink.siilm.core.file.StoragePathAllocator
import ink.siilm.core.event.EventService
import ink.siilm.shared.config.LandropProperties
import ink.siilm.gateway.auth.JwtConfig
import ink.siilm.gateway.auth.JwtValidator
import ink.siilm.gateway.codec.JsonCodec
import ink.siilm.gateway.codec.ProtobufCodec
import ink.siilm.gateway.file.FileChannelOps
import ink.siilm.gateway.http.HttpApiRoutes
import ink.siilm.gateway.ws.WebSocketFrameHandler
import ink.siilm.gateway.ws.WebSocketSessionManager
import ink.siilm.shared.bridge.CoreBridge
import ink.siilm.shared.config.ServerConfig
import org.koin.dsl.module

val gatewayModule = module {
    single { ServerConfig() }
    single { CoreBridge(get()) }
    single { JwtConfig() }
    single { JwtValidator(get()) }
    single { JsonCodec() }
    single { ProtobufCodec() }

    // core 服务
    single { RoomManager() }
    single { RoomJoinService(get()) }
    single { PermissionService(get()) }
    single { ChatHistoryService(get(), get()) }
    single { ServerConfigManager() }
    single { JwtIssuer() }
    single { ChallengeManager() }
    single { AuthService(get(), get()) }
    single { StoragePathAllocator(java.nio.file.Path.of(LandropProperties.getFileBaseDir()), LandropProperties.getStorageMode(), java.nio.file.Path.of(LandropProperties.getStorageCloudCacheDir())) }
    single { EventService() }

    // gateway
    single { WebSocketSessionManager(get(), get()) }
    single { WebSocketFrameHandler(get(), get(), get(), get(), get(), get(), get(), get()) }
    single { FileChannelOps(get()) }
    single { HttpApiRoutes(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
}
