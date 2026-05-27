package ink.siilm.core.auth

import ink.siilm.core.persistence.GlobalRoleTable
import ink.siilm.core.persistence.UserTable
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.io.File
import java.security.KeyPairGenerator
import java.util.*

/**
 * Owner 初始化器。首次部署时生成 Ed25519 密钥对，每次重部署均全新生成。
 *
 * 密钥文件布局（均在 owner-secret/ 下）：
 * - <username>.key — 私钥（Base64，仅 owner 可读）
 * - <username>.pub — 公钥（Base64，用于服务端验签）
 *
 * 如果 users 表为空：
 *   1. 清理 owner-secret/ 下所有旧有密钥文件
 *   2. 生成新密钥对，持久化到 owner-secret/ 后注册 Owner
 */
object OwnerInitializer {
    private const val OWNER_SECRET_DIR = "owner-secret"

    fun initialize() {
        transaction {
            val userCount = UserTable.selectAll().count()
            if (userCount > 0) {
                log.info("Users table already contains {} user(s), skipping owner init", userCount)
                return@transaction
            }

            val secretDir = File(OWNER_SECRET_DIR)
            if (!secretDir.exists()) secretDir.mkdirs()

            // 1. 清理所有旧有密钥文件
            secretDir.listFiles()?.forEach { file ->
                if (file.isFile && (file.name.endsWith(".key") || file.name.endsWith(".pub"))) {
                    file.delete()
                    log.info("Removed old key file: {}", file.name)
                }
            }

            // 2. 生成新密钥对
            generateNewOwnerKey(secretDir)
        }
    }

    private fun generateNewOwnerKey(secretDir: File) {
        log.info("=== Generating new system Owner key pair ===")

        val username = generateOwnerUsername()
        val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val publicKeyBase64 = Base64.getEncoder().encodeToString(keyPair.public.encoded)
        val privateKeyBase64 = Base64.getEncoder().encodeToString(keyPair.private.encoded)
        val userId = generateOwnerUserId()
        val now = System.currentTimeMillis()

        UserTable.insert {
            it[UserTable.userId] = userId
            it[UserTable.username] = username
            it[UserTable.publicKey] = publicKeyBase64
            it[UserTable.displayName] = "System Owner"
            it[UserTable.globalRole] = "owner"
            it[UserTable.createdAt] = now
            it[UserTable.isActive] = 1
        }
        GlobalRoleTable.insert {
            it[GlobalRoleTable.userId] = userId
            it[GlobalRoleTable.role] = "owner"
            it[GlobalRoleTable.grantedAt] = now
        }

        // 写入私钥文件
        val privateKeyFile = File(secretDir, "${username}.key")
        privateKeyFile.writeText(privateKeyBase64 + "\n")
        privateKeyFile.setReadable(false, false)
        privateKeyFile.setReadable(true, true)

        // 写入公钥文件（供服务端恢复时读取）
        val publicKeyFile = File(secretDir, "${username}.pub")
        publicKeyFile.writeText(publicKeyBase64 + "\n")

        log.info("=== Owner init complete ===")
        log.info("  Username:    {}", username)
        log.info("  User ID:     {}", userId)
        log.info("  Private key: {}", privateKeyFile.absolutePath)
        log.info("  Public key:  {}", publicKeyFile.absolutePath)
        log.info("  Your Private Key: {}", privateKeyBase64)
        log.info("  This information will only print once, take care of your keyfile.")
    }

    private fun generateOwnerUsername(): String {
        val hex = (1..8).map { "0123456789abcdef".random() }.joinToString("")
        return "owner_$hex"
    }

    private fun generateOwnerUserId(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return (1..12).map { chars.random() }.joinToString("")
    }

    private val log = LoggerFactory.getLogger(OwnerInitializer::class.java)
}
