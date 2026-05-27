package ink.siilm.core

import ink.siilm.core.file.FileMetaManager
import ink.siilm.core.message.MessageRouter
import ink.siilm.core.room.RoomManager
import ink.siilm.core.user.UserManager
import ink.siilm.proto.InternalComm.*
import ink.siilm.shared.bridge.CoreBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.*

class CoreEngine(
    private val bridge: CoreBridge,
    private val messageRouter: MessageRouter,
    private val roomManager: RoomManager,
    private val userManager: UserManager,
    private val fileMetaManager: FileMetaManager,
    private val scope: CoroutineScope
) {
    fun start(): Job = scope.launch(Dispatchers.Default) {
        log.info("CoreEngine started")
        for (envelope in bridge.coreInboundChannel()) {
            try { dispatch(envelope) }
            catch (e: Exception) { log.error("Error", e); sendError(envelope.messageId) }
        }
        log.info("CoreEngine stopped")
    }

    private suspend fun dispatch(envelope: Envelope) {
        when {
            envelope.hasSessionEvent()    -> handleSessionEvent(envelope)
            envelope.hasMessageDelivery() -> messageRouter.route(envelope)
            envelope.hasFileInstruction() -> handleFileInstruction(envelope)
            envelope.hasRoomCommand()     -> handleRoomCommand(envelope)
            else -> log.warn("Unknown payload")
        }
    }

    private fun handleSessionEvent(envelope: Envelope) {
        val event = envelope.sessionEvent
        val sessionId = envelope.sessionId
        when (event.type) {
            SessionEvent.Type.CONNECTED -> userManager.onConnected(sessionId, event.userId, event.userId, "member", null)
            SessionEvent.Type.DISCONNECTED -> { userManager.onDisconnected(sessionId); roomManager.notifyUserLeft(event.userId) }
            SessionEvent.Type.KICKED -> { userManager.onKicked(sessionId); roomManager.notifyUserLeft(event.userId) }
            SessionEvent.Type.PING -> log.trace("PING session={}", sessionId)
            else -> {}
        }
    }

    private suspend fun handleFileInstruction(envelope: Envelope) {
        val cmd = envelope.fileInstruction
        when (cmd.command) {
            FileInstruction.Command.REQUEST_UPLOAD_SLOT -> {
                // 插入 files 临时中转记录
                fileMetaManager.insertTempRecord(
                    roomId = cmd.roomId.ifBlank { "" },
                    fileId = cmd.fileId,
                    fileHash = "",  // 上传完成后 UPLOAD_COMPLETED 更新
                    userId = cmd.userId
                )
                val storagePath = fileMetaManager.allocator.allocatePath(cmd.fileId, cmd.fileName.ifBlank { "upload" })
                val resp = Envelope.newBuilder().setMessageId(UUID.randomUUID().toString())
                    .setTimestamp(System.currentTimeMillis())
                    .setFileInstruction(FileInstruction.newBuilder().setCommand(FileInstruction.Command.UPLOAD_SLOT_GRANTED)
                        .setFileId(cmd.fileId).setStoragePath(storagePath.toString()).build())
                    .build()
                bridge.coreOutboundChannel().send(resp)
            }
            FileInstruction.Command.UPLOAD_COMPLETED -> {
                // 上传完成：gateway 已写入 room_files，这里只清理中转记录
                fileMetaManager.deleteTempRecord(cmd.fileId)
                log.info("Upload completed: fileId={}, roomId={}", cmd.fileId, cmd.roomId)
            }
            else -> log.debug("FileInstruction: {}", cmd.command)
        }
    }

    private suspend fun handleRoomCommand(envelope: Envelope) {
        val cmd = envelope.roomCommand
        when (cmd.action) {
            RoomCommand.Action.CREATE -> {
                roomManager.createRoom(cmd.roomName, cmd.userId)
            }
            RoomCommand.Action.JOIN -> {
                roomManager.joinRoom(cmd.roomId, cmd.userId)
            }
            RoomCommand.Action.LEAVE -> roomManager.leaveRoom(cmd.roomId, cmd.userId)
            RoomCommand.Action.LIST -> {
                val rooms = roomManager.listRooms()
                val resp = Envelope.newBuilder().setMessageId(UUID.randomUUID().toString())
                    .setTimestamp(System.currentTimeMillis())
                    .setRoomCommand(RoomCommand.newBuilder().setAction(RoomCommand.Action.LIST).addAllRooms(rooms).build())
                    .build()
                bridge.coreOutboundChannel().send(resp)
            }
            else -> log.debug("RoomCommand: {}", cmd.action)
        }
    }

    private suspend fun sendError(refMessageId: String) {
        val err = Envelope.newBuilder().setMessageId(UUID.randomUUID().toString())
            .setTimestamp(System.currentTimeMillis())
            .setError(ErrorInfo.newBuilder().setRefMessageId(refMessageId).setErrorCode(500).setErrorMessage("Internal error").setErrorType(ErrorInfo.ErrorType.INTERNAL_ERROR).build())
            .build()
        bridge.coreOutboundChannel().send(err)
    }

    companion object {
        private val log = LoggerFactory.getLogger(CoreEngine::class.java)
    }
}
