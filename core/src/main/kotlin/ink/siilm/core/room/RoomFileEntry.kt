package ink.siilm.core.room

/**
 * 房间文件条目（从 RoomManager 提取为独立顶层类，避免嵌套类加载问题）。
 */
data class RoomFileEntry(
    val roomId: String,
    val fileId: String,
    val fileName: String,
    val fileSize: Long,
    val mimeType: String,
    val storagePath: String,
    val uploadedAt: Long
)
