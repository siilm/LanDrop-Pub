package ink.siilm.core.file

import ink.siilm.core.persistence.FileTable
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.time.LocalDate
import java.util.*

class StoragePathAllocator(
    private val baseDir: Path = Path.of("./landrop-files"),
    private val storageMode: String = "local",
    private val cloudCacheDir: Path = Path.of("./cloud-cache")
) {
    fun allocatePath(fileId: String, originalFileName: String): Path {
        val dateDir = LocalDate.now().toString()
        val safeName = originalFileName.replace(Regex("[^a-zA-Z0-9._\\-]"), "_")
        val dir = if (storageMode == "cloud") cloudCacheDir.resolve(dateDir)
                  else baseDir.resolve("files").resolve(dateDir)
        dir.toFile().mkdirs()
        return dir.resolve("${fileId}_${safeName}").toAbsolutePath()
    }

    // TODO: 未来云端功能启用后使用
    fun allocateAvatarPath(userId: String): Path {
        val dir = if (storageMode == "cloud") cloudCacheDir.resolve("avatar")
                  else baseDir.resolve(userId).resolve("avatar")
        dir.toFile().mkdirs()
        return dir.resolve("${userId}.jpg").toAbsolutePath()
    }

    // TODO: 未来云端功能启用后使用
    fun allocateChatImagePath(roomId: String): Path {
        val dir = if (storageMode == "cloud") cloudCacheDir.resolve("img").resolve(roomId)
                  else baseDir.resolve(roomId).resolve("chatimg")
        dir.toFile().mkdirs()
        return dir.resolve("${UUID.randomUUID()}.jpg").toAbsolutePath()
    }

    // TODO: 未来云端功能启用后使用
    fun getStorageMode() = storageMode
    // TODO: 未来云端功能启用后使用
    fun getCloudCacheDir() = cloudCacheDir
}

/**
 * 文件中转管理器 — 管理 `files` 临时表。
 *
 * 文件上传请求暂存到 `files` 表，校验完成后移入 `room_files`。
 * CleanupJobs 定期清理过期记录。
 */
class FileMetaManager(val allocator: StoragePathAllocator) {

    data class TempFileEntry(
        val id: Int,
        val roomId: String,
        val fileId: String,
        val fileHash: String,
        val userId: String,
        val startedAt: Long,
        val expiresAt: Long
    )

    /** 插入一条上传中转记录。 */
    fun insertTempRecord(roomId: String, fileId: String, fileHash: String, userId: String): TempFileEntry {
        val now = System.currentTimeMillis()
        val expiresAt = now + 48 * 3600 * 1000L  // 48 小时
        return transaction {
            FileTable.insert {
                it[FileTable.roomId] = roomId
                it[FileTable.fileId] = fileId
                it[FileTable.fileHash] = fileHash
                it[FileTable.userId] = userId
                it[FileTable.startedAt] = now
                it[FileTable.expiresAt] = expiresAt
            }
            val row = FileTable.selectAll().where { FileTable.fileId eq fileId }.single()
            TempFileEntry(row[FileTable.id], row[FileTable.roomId], row[FileTable.fileId],
                row[FileTable.fileHash], row[FileTable.userId], row[FileTable.startedAt], row[FileTable.expiresAt])
        }
    }

    /** 删除中转记录（上传完成后调用）。 */
    fun deleteTempRecord(fileId: String) {
        transaction { FileTable.deleteWhere { FileTable.fileId eq fileId } }
    }

    /** 清理过期中转记录。 */
    fun cleanupExpired(): Int {
        val now = System.currentTimeMillis()
        return transaction {
            val expired = FileTable.selectAll().filter { it[FileTable.expiresAt] < now }
            expired.forEach { row ->
                FileTable.deleteWhere { FileTable.fileId eq row[FileTable.fileId] }
            }
            if (expired.isNotEmpty()) log.info("TempFileCleanup: removed {} expired upload record(s)", expired.size)
            expired.size
        }
    }

    companion object { private val log = LoggerFactory.getLogger(FileMetaManager::class.java) }
}
