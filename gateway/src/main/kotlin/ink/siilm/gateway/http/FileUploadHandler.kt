package ink.siilm.gateway.http

import ink.siilm.gateway.codec.JsonCodec
import ink.siilm.gateway.file.FileChannelOps
import ink.siilm.gateway.model.http.response.FileUploadSuccessResponse
import ink.siilm.core.file.StoragePathAllocator
import ink.siilm.core.room.RoomManager
import ink.siilm.proto.InternalComm
import ink.siilm.proto.InternalComm.Envelope
import ink.siilm.shared.bridge.CoreBridge
import ink.siilm.shared.config.ServerConfig
import ink.siilm.shared.exception.GatewayException
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import io.ktor.utils.io.toByteArray
import java.nio.channels.Channels
import java.util.*
import kotlin.io.path.writeBytes

/**
 * 文件上传处理。
 *
 * 流程：
 * 1. 解析 multipart 请求，提取文件信息和字节流
 * 2. 向 core 请求上传槽位（[InternalComm.FileInstruction.Command.REQUEST_UPLOAD_SLOT]）
 * 3. 将接收到的字节流通过 [FileChannelOps.transferFrom] 零拷贝写入磁盘
 * 4. 完成后通知 core（[InternalComm.FileInstruction.Command.UPLOAD_COMPLETED]）
 *
 * Ktor 3.x multipart API: receiveMultipart() 返回 MultiPartData,
 * 通过 readAllParts() 或 forEachPart {} 遍历。
 */
class FileUploadHandler(
    private val config: ServerConfig,
    private val bridge: CoreBridge,
    private val fileChannelOps: FileChannelOps,
    private val jsonCodec: JsonCodec,
    private val storageAllocator: StoragePathAllocator,
    private val roomManager: RoomManager
) {
    /**
     * 处理文件上传请求。
     *
     * @param userId 上传者 userId
     * @param call Ktor ApplicationCall
     * @return Pair<HttpStatusCode, Any> 响应状态码和响应体
     */
    suspend fun handle(userId: String, call: ApplicationCall): Pair<HttpStatusCode, Any> {
        // 1. 解析 multipart
        val multipart = call.receiveMultipart()
        var fileName: String? = null
        var fileSize = 0L
        var fileBytes: ByteArray? = null
        var roomId: String? = null

        multipart.forEachPart { part ->
            when (part) {
                is PartData.FileItem -> {
                    fileName = part.originalFileName ?: "unknown"
                    fileBytes = part.provider().toByteArray()
                    fileSize = fileBytes!!.size.toLong()

                    // 大小检查
                    val maxBytes = config.maxFileSizeMB * 1024 * 1024
                    if (fileSize > maxBytes) {
                        part.dispose()
                        throw GatewayException(
                            message = "File size $fileSize exceeds max ${config.maxFileSizeMB}MB",
                            errorCode = 2003
                        )
                    }
                }
                is PartData.FormItem -> {
                    if (part.name == "room_id") {
                        roomId = part.value
                    }
                }
                else -> { /* 忽略非文件 part */ }
            }
            part.dispose()
        }

        if (fileName == null || fileBytes == null) {
            return Pair(HttpStatusCode.BadRequest, mapOf("error" to "No file provided"))
        }
        if (roomId.isNullOrBlank()) {
            return Pair(HttpStatusCode.BadRequest, mapOf("error" to "missing_room_id"))
        }

        val fileId = UUID.randomUUID().toString()

        // 2. 向 core 请求上传槽位
        val slotRequest = Envelope.newBuilder()
            .setMessageId(UUID.randomUUID().toString())
            .setTimestamp(System.currentTimeMillis())
            .setFileInstruction(
                InternalComm.FileInstruction.newBuilder()
                    .setCommand(InternalComm.FileInstruction.Command.REQUEST_UPLOAD_SLOT)
                    .setUserId(userId)
                    .setFileId(fileId)
                    .setFileName(fileName!!)
                    .setMaxSize(fileSize)
                    .also { if (roomId != null) it.roomId = roomId }
            )
            .build()
        bridge.sendToCore(slotRequest)

        // 3. 写入磁盘（零拷贝）
        val destPath = storageAllocator.allocatePath(fileId, fileName!!)
        fileChannelOps.ensureDirectory(destPath.parent)
        val sourceChannel = Channels.newChannel(fileBytes.inputStream())

        try {
            fileChannelOps.transferFrom(
                source = sourceChannel as java.nio.channels.FileChannel,
                destPath = destPath,
                fileSize = fileSize
            )
        } catch (e: ClassCastException) {
            // InputStream 的 Channel 不是 FileChannel，降级为普通写入
            log.debug("Source channel is not FileChannel, falling back to regular write")
            destPath.writeBytes(fileBytes)
        } finally {
            withContext(Dispatchers.IO) {
                sourceChannel.close()
            }
        }

        // 计算 SHA-256
        val checksum = java.security.MessageDigest.getInstance("SHA-256")
            .digest(fileBytes).joinToString("") { "%02x".format(it) }

        log.info("File uploaded: fileId={}, fileName={}, size={}, checksum={}, path={}", fileId, fileName, fileSize, checksum, destPath)

        // 3.5. 立即写入 room_files（消除竞态：客户端可能在 core 处理 UPLOAD_COMPLETED 之前就发 chat_message）
        roomManager.addRoomFile(
            roomId = roomId!!,
            fileName = destPath.fileName.toString(),
            fileSize = fileSize,
            mimeType = "application/octet-stream",
            storagePath = destPath.toString(),
            checksum = checksum,
            fileId = fileId
        )

        // 4. 通知 core 上传完成（携带完整文件信息，供 core 写入 room_files）
        val completeEvent = Envelope.newBuilder()
            .setMessageId(UUID.randomUUID().toString())
            .setTimestamp(System.currentTimeMillis())
            .setFileInstruction(
                InternalComm.FileInstruction.newBuilder()
                    .setCommand(InternalComm.FileInstruction.Command.UPLOAD_COMPLETED)
                    .setUserId(userId)
                    .setFileId(fileId)
                    .setFileName(destPath.fileName.toString())
                    .setStoragePath(destPath.toString())
                    .setMaxSize(fileSize)
                    .setChecksum(checksum)
                    .also { if (roomId != null) it.roomId = roomId }
            )
            .build()
        bridge.sendToCore(completeEvent)

        // 5. 返回成功响应
        return Pair(
            HttpStatusCode.OK,
            FileUploadSuccessResponse(
                fileId = fileId,
                fileName = fileName!!,
                fileSize = fileSize.toString(),
                status = "uploaded"
            )
        )
    }

    companion object {
        private val log = LoggerFactory.getLogger(FileUploadHandler::class.java)
    }
}