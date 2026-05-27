package ink.siilm.gateway.file

import ink.siilm.shared.config.ServerConfig
import ink.siilm.shared.exception.GatewayException
import org.slf4j.LoggerFactory
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.WritableByteChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * 零拷贝文件 I/O 操作封装。
 *
 * 利用 [FileChannel.transferFrom] 和 [FileChannel.transferTo]，
 * 底层触发 Linux [sendfile()] 系统调用，数据不经过用户空间缓冲区，
 * 极大降低 CPU 开销和内存占用。
 *
 * 参照 [`gateway-implementation-plan.md`](../../../../LanDrop-Doc/gateway-implementation-plan.md) §6 步骤 9
 * 参照 [`gateway-design.md`](../../../../LanDrop-Doc/gateway-design.md) §5.4
 *
 * ## 协程调度
 * 本类所有 I/O 方法均为阻塞操作，调用方必须在 [Dispatchers.IO] 上执行。
 */
class FileChannelOps(
    private val config: ServerConfig
) {
    // ═══════════════════════════════════════════════════════════
    // 写入：客户端 → 磁盘（transferFrom）
    // ═══════════════════════════════════════════════════════════

    /**
     * 零拷贝写入：从源 [FileChannel] 传输数据到磁盘文件。
     *
     * 典型场景：客户端通过 HTTP multipart 上传文件，
     * 网关将接收到的字节流通过本方法写入服务器磁盘。
     *
     * @param source 源 Channel（如客户端上传流的 Channel）
     * @param destPath 目标文件路径
     * @param fileSize 文件总大小（字节），用于预分配磁盘空间
     *
     * @throws [GatewayException] 文件大小超过 [ServerConfig.maxFileSizeMB] 限制
     */
    fun transferFrom(source: FileChannel, destPath: Path, fileSize: Long) {
        // 大小检查
        val maxFileSizeBytes = config.maxFileSizeMB * 1024 * 1024
        if (fileSize > maxFileSizeBytes) {
            throw GatewayException(
                message = "File size $fileSize exceeds max ${config.maxFileSizeMB}MB",
                errorCode = 2003
            )
        }

        // 确保父目录存在
        Files.createDirectories(destPath.parent)

        RandomAccessFile(destPath.toFile(), "rw").use { raf ->
            val destChannel = raf.channel

            // 预分配磁盘空间，减少碎片
            raf.setLength(fileSize)

            var position = 0L
            var remaining = fileSize

            // 循环传输，确保全部字节写入
            // transferFrom 单次可能不传输完整量，需要循环
            while (remaining > 0) {
                val transferred = destChannel.transferFrom(source, position, remaining)
                if (transferred <= 0) {
                    throw GatewayException(
                        message = "transferFrom returned $transferred at position $position, remaining $remaining",
                        errorCode = 2003
                    )
                }
                position += transferred
                remaining -= transferred
            }

            log.debug("transferFrom completed: dest={}, size={}, path={}", destPath.fileName, fileSize, destPath)
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 发送：磁盘 → 客户端（transferTo）
    // ═══════════════════════════════════════════════════════════

    /**
     * 零拷贝发送：从磁盘文件传输数据到目标 [WritableByteChannel]。
     *
     * 典型场景：客户端请求下载文件，网关从磁盘读取并通过 WebSocket/HTTP 发送。
     *
     * @param filePath 源文件路径
     * @param target 目标 Channel（如客户端响应流的 Channel）
     * @param startPosition 起始偏移量（支持断点续传，0 表示从头开始）
     * @return 实际传输的字节数
     */
    fun transferTo(filePath: Path, target: WritableByteChannel, startPosition: Long = 0L): Long {
        val fileSize = Files.size(filePath)

        RandomAccessFile(filePath.toFile(), "r").use { raf ->
            val srcChannel = raf.channel

            // 定位到续传偏移量
            if (startPosition > 0) {
                srcChannel.position(startPosition)
            }

            var position = startPosition
            val totalToSend = fileSize - startPosition
            var remaining = totalToSend

            // 循环传输
            while (remaining > 0) {
                val transferred = srcChannel.transferTo(position, remaining, target)
                if (transferred <= 0) {
                    throw GatewayException(
                        message = "transferTo returned $transferred at position $position, remaining $remaining",
                        errorCode = 2003
                    )
                }
                position += transferred
                remaining -= transferred
            }

            val totalTransferred = position - startPosition
            log.debug("transferTo completed: file={}, startPos={}, transferred={}",
                filePath.fileName, startPosition, totalTransferred)

            return totalTransferred
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 辅助操作
    // ═══════════════════════════════════════════════════════════

    /**
     * 确保目标目录存在。
     */
    fun ensureDirectory(dirPath: Path) {
        if (!Files.exists(dirPath)) {
            Files.createDirectories(dirPath)
            log.info("Created directory: {}", dirPath)
        }
    }

    /**
     * 获取文件大小（字节），文件不存在返回 -1。
     */
    fun fileSize(filePath: Path): Long {
        return if (Files.exists(filePath)) Files.size(filePath) else -1L
    }

    /**
     * 删除文件。
     *
     * @return true 删除成功，false 文件不存在
     */
    fun deleteFile(filePath: Path): Boolean {
        return try {
            Files.deleteIfExists(filePath)
        } catch (e: Exception) {
            log.warn("Failed to delete file: {}", filePath, e)
            false
        }
    }

    /**
     * 检查文件是否存在且可读。
     */
    fun isReadable(filePath: Path): Boolean {
        return Files.isReadable(filePath) && Files.isRegularFile(filePath)
    }

    /**
     * 打开文件的 [FileChannel]（只读模式），调用方负责关闭。
     *
     * 用于需要灵活控制 Channel 生命周期的场景。
     */
    fun openReadChannel(filePath: Path): FileChannel {
        return FileChannel.open(filePath, StandardOpenOption.READ)
    }

    companion object {
        private val log = LoggerFactory.getLogger(FileChannelOps::class.java)
    }
}