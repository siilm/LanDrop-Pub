package ink.siilm.core.crypto

import java.security.MessageDigest

/**
 * SHA-256 哈希工具。
 *
 * 用于：
 * - 设备指纹: SHA-256(username + compact_json(device_info))
 * - refresh token 存储: SHA-256(refresh_token) 存入 user_sessions
 * - 文件校验: SHA-256(file_content)
 */
object Sha256Util {

    private const val ALGORITHM = "SHA-256"

    /**
     * 计算 SHA-256 哈希，返回原始字节数组。
     */
    fun hash(data: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance(ALGORITHM)
        return digest.digest(data)
    }

    /**
     * 计算 SHA-256 哈希，返回十六进制小写字符串。
     */
    fun hashHex(data: ByteArray): String {
        return hash(data).joinToString("") { "%02x".format(it) }
    }

    /**
     * 计算 SHA-256 哈希，返回十六进制小写字符串。
     */
    fun hashHex(input: String): String {
        return hashHex(input.toByteArray(Charsets.UTF_8))
    }
}
