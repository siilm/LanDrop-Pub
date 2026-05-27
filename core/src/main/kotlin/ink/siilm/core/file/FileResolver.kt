package ink.siilm.core.file

/**
 * 文件 URL 解析工具。
 *
 * 将服务端本地存储路径转换为客户端可访问的 API URL。
 * - HTTP/HTTPS URL → 直接返回
 * - 本地路径 → 转换为 /api/getfiles/... 端点地址
 *
 * ★ 安全关键: 绝不返回服务端绝对路径给客户端。
 */
object FileResolver {

    /** 默认头像（系统全局常量） */
    const val DEFAULT_AVATAR_URL =
        "https://vip.123pan.cn/1824698339/yk6baz03t0n000ddx3d681hudu4a9gbeDIYvAdQ2BdDwBGxwAwUvAa==.png"

    /**
     * 解析头像 URL。
     *
     * 处理逻辑：
     *   1. avatar_url 为 "NONE_URL" 或空 → 返回默认头像
     *   2. avatar_url 为 HTTP/HTTPS 链接（含图床） → 直接返回
     *   3. avatar_url 为本地路径 → 转换为 /api/getfiles/avatar/ 端点
     *
     * @param avatarUrl DB 中存储的 avatar_url 值
     * @param userId 用户 ID
     * @return 客户端可用的头像 URL
     */
    fun resolveAvatarUrl(avatarUrl: String, userId: String): String {
        // 未设置头像 → 返回默认头像
        if (avatarUrl == "NONE_URL" || avatarUrl.isBlank()) {
            return DEFAULT_AVATAR_URL
        }
        // 图床 / 外部 URL → 直接返回
        if (avatarUrl.startsWith("http://") || avatarUrl.startsWith("https://")) {
            return avatarUrl
        }
        // 本地路径 → 通过静态文件 API 暴露（客户端无需关心扩展名）
        return "/api/getfiles/avatar/$userId"
    }

    /**
     * 解析房间文件/图片 URL。
     *
     * @param roomId 房间 ID
     * @param fileName 文件名（含相对路径，如 "chatimg/uuid.jpg" 或 "files/fileId_name.ext"）
     * @return 客户端可用的文件 URL
     */
}
