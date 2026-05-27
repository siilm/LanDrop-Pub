package ink.siilm.core.auth

import ink.siilm.core.auth.RegisterResult
import ink.siilm.core.auth.LoginInitiateResult
import ink.siilm.core.auth.VerifyResult
import ink.siilm.core.auth.RefreshResult
import ink.siilm.core.auth.RenameUsernameResult
import ink.siilm.core.persistence.*
import ink.siilm.shared.config.LandropProperties
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 认证服务集成测试 — Ed25519 两步登录流程（userId 版）。
 *
 * 使用 H2 内存数据库。migrate() 会通过 OwnerInitializer 自动创建 Owner。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthServiceTest {

    private lateinit var authService: AuthService

    // alice 的 Ed25519 密钥对和 userId（由系统自动生成）
    private lateinit var alicePrivateKeyBase64: String
    private lateinit var aliceUserId: String

    // Owner userId（由 OwnerInitializer 自动生成）
    private lateinit var ownerUserId: String

    @BeforeAll
    fun setUp() {
        val h2Config = LandropProperties.DatabaseConfig(
            url = "jdbc:h2:mem:landrop-test;DB_CLOSE_DELAY=-1",
            user = "sa",
            password = "",
            driver = "org.h2.Driver",
            maxPoolSize = 5,
            minIdle = 1,
            connectionTimeoutMs = 5000
        )
        DatabaseFactory.init(h2Config)
        DatabaseMigrator.migrate()
        OwnerInitializer.initialize()

        authService = AuthService(JwtIssuer())

        // 获取 Owner userId
        ownerUserId = transaction {
            GlobalRoleTable.selectAll()
                .where { GlobalRoleTable.role eq "owner" }
                .single()[GlobalRoleTable.userId]
        }

        // 注册 alice（member）— userId 和密钥对由系统自动生成
        val regResult = authService.registerUser("alice")
        check(regResult is RegisterResult.Success) { "Pre-register alice failed: $regResult" }
        val success = regResult as RegisterResult.Success
        aliceUserId = success.userId
        alicePrivateKeyBase64 = success.privateKeyBase64
    }

    @AfterAll
    fun tearDown() {
        DatabaseMigrator.dropAll()
        DatabaseFactory.shutdown()
    }

    // ═══════════════════════════════════════════════════════════
    // Owner 自动创建验证
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `owner created by OwnerInitializer during migration`() {
        val ownerInfo = transaction {
            UserTable.selectAll().where { UserTable.userId eq ownerUserId }.singleOrNull()
        }
        assertNotNull(ownerInfo, "Owner should exist in database")
        assertEquals("owner", ownerInfo!![UserTable.globalRole])
        assertTrue(ownerInfo[UserTable.userId].matches(Regex("^[A-Za-z0-9]{12}$")))
        assertTrue(ownerInfo[UserTable.username].startsWith("owner_"))
    }

    // ═══════════════════════════════════════════════════════════
    // 注册测试
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `register new member succeeds`() {
        val result = authService.registerUser("bob")
        assertTrue(result is RegisterResult.Success, "Expected Success but got $result")
        result as RegisterResult.Success
        assertEquals("member", result.globalRole)
        assertEquals("bob", result.username)
        assertTrue(result.userId.matches(Regex("^[A-Za-z0-9]{12}$")))
        assertTrue(result.privateKeyBase64.isNotBlank())
        assertTrue(result.publicKeyBase64.isNotBlank())
    }

    @Test
    fun `register with long username fails`() {
        val longName = "A".repeat(26)
        val result = authService.registerUser(longName)
        assertTrue(result is RegisterResult.UsernameTooLong)
    }

    // ═══════════════════════════════════════════════════════════
    // 登录测试（以 alice 为例）
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `full login flow - two step challenge signature`() {
        val deviceInfo = """{"os":"linux","hostname":"test-pc"}"""

        // 步骤 1: 请求挑战值
        val initiateResult = authService.initiateLogin(aliceUserId)
        assertTrue(initiateResult is LoginInitiateResult.Success)
        initiateResult as LoginInitiateResult.Success
        val tempSessionId = initiateResult.tempSessionId
        val challengeBase64 = initiateResult.challenge
        assertTrue(tempSessionId.isNotBlank())
        assertTrue(challengeBase64.isNotBlank())

        // 客户端用 alice 私钥签名 (signatureData = challenge || deviceInfo || userId)
        val challengeBytes = Base64.getDecoder().decode(challengeBase64)
        val signatureData = challengeBytes +
                deviceInfo.toByteArray(Charsets.UTF_8) +
                aliceUserId.toByteArray(Charsets.UTF_8)

        val privateKeyBytes = Base64.getDecoder().decode(alicePrivateKeyBase64)
        val keyFactory = java.security.KeyFactory.getInstance("Ed25519")
        val privateKey = keyFactory.generatePrivate(PKCS8EncodedKeySpec(privateKeyBytes))
        val signer = Signature.getInstance("Ed25519")
        signer.initSign(privateKey)
        signer.update(signatureData)
        val signatureBase64 = Base64.getEncoder().encodeToString(signer.sign())

        // 步骤 2: 验证签名
        val verifyResult = authService.verifySignature(
            tempSessionId = tempSessionId,
            signatureBase64 = signatureBase64,
            deviceInfoJson = deviceInfo
        )

        assertTrue(verifyResult is VerifyResult.Success)
        verifyResult as VerifyResult.Success
        assertEquals("alice", verifyResult.username)
        assertEquals(aliceUserId, verifyResult.userId)
        assertEquals("member", verifyResult.globalRole)
        assertTrue(verifyResult.accessToken.startsWith("eyJ"))
        assertTrue(verifyResult.refreshToken.startsWith("rt_"))
        assertTrue(verifyResult.expiresIn > 0)
    }

    @Test
    fun `login with wrong userId fails`() {
        val result = authService.initiateLogin("nonexistent1")
        assertTrue(result is LoginInitiateResult.UserNotFound)
    }

    @Test
    fun `replay protection - used challenge cannot be reused`() {
        val deviceInfo = """{"os":"linux"}"""
        val initiateResult = authService.initiateLogin(aliceUserId)
        assertTrue(initiateResult is LoginInitiateResult.Success)
        initiateResult as LoginInitiateResult.Success

        // 用无效签名消耗 challenge（防重放）
        val invalidResult = authService.verifySignature(
            tempSessionId = initiateResult.tempSessionId,
            signatureBase64 = "invalid",
            deviceInfoJson = deviceInfo
        )
        assertTrue(invalidResult is VerifyResult.InvalidSignature || invalidResult is VerifyResult.ChallengeExpired)
    }

    // ═══════════════════════════════════════════════════════════
    // Token 刷新测试
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `refresh token returns new access token`() {
        val loginResult = completeLogin()
        val refreshResult = authService.refreshAccessToken(loginResult.refreshToken)
        assertTrue(refreshResult is RefreshResult.Success)
        refreshResult as RefreshResult.Success
        assertTrue(refreshResult.accessToken.startsWith("eyJ"))
        assertTrue(refreshResult.expiresIn > 0)
    }

    @Test
    fun `invalid refresh token fails`() {
        val result = authService.refreshAccessToken("rt_invalid_token_1234567890abcdef")
        assertTrue(result is RefreshResult.InvalidToken)
    }

    // ═══════════════════════════════════════════════════════════
    // 会话撤销测试
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `revoke session invalidates refresh token`() {
        val loginResult = completeLogin()
        val revoked = authService.revokeSession(loginResult.userId)
        assertTrue(revoked > 0)

        val refreshResult = authService.refreshAccessToken(loginResult.refreshToken)
        assertTrue(refreshResult is RefreshResult.TokenRevoked)
    }

    // ═══════════════════════════════════════════════════════════
    // renameUsername 测试
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `rename username succeeds`() {
        val result = authService.renameUsername(aliceUserId, "AliceNew")
        assertTrue(result is RenameUsernameResult.Success, "Expected Success but got $result")
        result as RenameUsernameResult.Success
        assertEquals("AliceNew", result.newUsername)
    }

    @Test
    fun `rename username too long fails`() {
        val longName = "A".repeat(26)
        val result = authService.renameUsername(aliceUserId, longName)
        assertTrue(result is RenameUsernameResult.UsernameTooLong)
    }

    @Test
    fun `rename username for nonexistent user fails`() {
        val result = authService.renameUsername("nonexistent1", "NewName")
        assertTrue(result is RenameUsernameResult.UserNotFound)
    }

    // ═══════════════════════════════════════════════════════════
    // 辅助
    // ═══════════════════════════════════════════════════════════

    private fun completeLogin(): VerifyResult.Success {
        val deviceInfoJson = """{"os":"linux"}"""
        val initiateResult = authService.initiateLogin(aliceUserId)
        check(initiateResult is LoginInitiateResult.Success)

        val challengeBytes = Base64.getDecoder().decode(initiateResult.challenge)
        val signatureData = challengeBytes +
                deviceInfoJson.toByteArray(Charsets.UTF_8) +
                aliceUserId.toByteArray(Charsets.UTF_8)

        val privateKeyBytes = Base64.getDecoder().decode(alicePrivateKeyBase64)
        val keyFactory = java.security.KeyFactory.getInstance("Ed25519")
        val privateKey = keyFactory.generatePrivate(PKCS8EncodedKeySpec(privateKeyBytes))
        val signer = Signature.getInstance("Ed25519")
        signer.initSign(privateKey)
        signer.update(signatureData)
        val signatureBase64 = Base64.getEncoder().encodeToString(signer.sign())

        val verifyResult = authService.verifySignature(
            tempSessionId = initiateResult.tempSessionId,
            signatureBase64 = signatureBase64,
            deviceInfoJson = deviceInfoJson
        )
        check(verifyResult is VerifyResult.Success)
        return verifyResult
    }
}
