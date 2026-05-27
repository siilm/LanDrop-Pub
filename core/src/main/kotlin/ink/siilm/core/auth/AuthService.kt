package ink.siilm.core.auth

import ink.siilm.core.crypto.ChallengeManager
import ink.siilm.core.crypto.Ed25519Util
import ink.siilm.core.crypto.Sha256Util
import ink.siilm.core.persistence.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import java.util.*

/**
 * 认证服务 — Ed25519 挑战签名 + JWT 双令牌体系。
 *
 * 完整流程（两步登录）：
 *   1. initiateLogin(username, deviceInfo) → challenge + temp_session_id
 *   2. verifySignature(tempSessionId, signature) → JWT + refresh_token
 *
 * 不依赖任何 Web 框架，纯业务逻辑。
 */
class AuthService(
    private val jwtIssuer: JwtIssuer,
    private val challengeManager: ChallengeManager = ChallengeManager()
) {
    private val random = SecureRandom()

    // ═══════════════════════════════════════════════════════════
    // 用户注册
    // ═══════════════════════════════════════════════════════════

    /**
     * 注册新用户。Ed25519 密钥对由系统自动生成。
     *
     * @param username 昵称（最长 25 字符）
     * @param userId 可选自定义用户 ID（12位字母数字，留空则自动生成）
     * @return 注册结果
     */
    fun registerUser(username: String, userId: String? = null): RegisterResult {
        if (username.length > 25) {
            log.error("Username too long: {} (max 25)", username.length)
            return RegisterResult.UsernameTooLong
        }

        val finalUserId: String
        if (userId != null) {
            if (!userId.matches(Regex("^[A-Za-z0-9]{12}$"))) {
                return RegisterResult.InvalidUserId
            }
            finalUserId = userId
        } else {
            finalUserId = generateUserId()
        }

        // 自动生成密钥对
        val (publicKeyBase64, privateKeyBase64) = Ed25519Util.generateKeyPair()

        return transaction {
            if (UserTable.selectAll().where { UserTable.userId eq finalUserId }.count() > 0L) {
                return@transaction RegisterResult.UserIdAlreadyExists
            }

            val now = System.currentTimeMillis()
            UserTable.insert {
                it[UserTable.userId] = finalUserId
                it[UserTable.username] = username
                it[UserTable.publicKey] = publicKeyBase64
                it[UserTable.displayName] = username
                it[UserTable.globalRole] = "member"
                it[UserTable.createdAt] = now
                it[UserTable.isActive] = 1
            }

            log.info("User registered: userId={}, username={}", finalUserId, username)

            RegisterResult.Success(
                userId = finalUserId,
                username = username,
                globalRole = "member",
                privateKeyBase64 = privateKeyBase64,
                publicKeyBase64 = publicKeyBase64
            )
        }
    }

    private fun generateUserId(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return (1..12).map { chars[random.nextInt(chars.length)] }.joinToString("")
    }

    // ═══════════════════════════════════════════════════════════
    // 登录步骤 1：请求挑战值
    // ═══════════════════════════════════════════════════════════

    /**
     * 登录步骤 1：生成挑战值。
     *
     * @param userId 用户 ID
     * @return 挑战值响应，或错误
     */
    fun initiateLogin(userId: String): LoginInitiateResult {
        return transaction {
            // 查找用户
            val userRow = UserTable.selectAll().where { UserTable.userId eq userId }.singleOrNull()
                ?: return@transaction LoginInitiateResult.UserNotFound

            if (userRow[UserTable.isActive] != 1) {
                return@transaction LoginInitiateResult.UserInactive
            }

            // 生成挑战值
            val (tempSessionId, challenge) = challengeManager.generate(userId)

            LoginInitiateResult.Success(
                tempSessionId = tempSessionId,
                challenge = challenge
            )
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 登录步骤 2：签名验证
    // ═══════════════════════════════════════════════════════════

    /**
     * 登录步骤 2：验证客户端 Ed25519 签名，签发 JWT + refresh token。
     *
     * @param tempSessionId 步骤 1 返回的临时会话 ID
     * @param signatureBase64 客户端 Ed25519 签名（Base64）
     * @param deviceInfoJson 步骤 1 相同设备信息，用于重建设备指纹
     * @return 认证结果（含 JWT + refresh token）
     */
    fun verifySignature(
        tempSessionId: String,
        signatureBase64: String,
        deviceInfoJson: String
    ): VerifyResult {
        // 1. 取出挑战值
        val challengeEntry = challengeManager.take(tempSessionId)
            ?: return VerifyResult.ChallengeExpired

        val userId = challengeEntry.userId
        val challengeBytes = challengeEntry.challenge

        return transaction {
            // 2. 查用户公钥
            val userRow = UserTable.selectAll().where { UserTable.userId eq userId }.singleOrNull()
                ?: return@transaction VerifyResult.UserNotFound

            if (userRow[UserTable.isActive] != 1) {
                return@transaction VerifyResult.UserInactive
            }

            val username = userRow[UserTable.username]
            val publicKey = userRow[UserTable.publicKey]
            val globalRole = userRow[UserTable.globalRole]

            // 3. 构造签名原文并验证
            val signatureData = Ed25519Util.buildSignatureData(
                challengeBytes,
                deviceInfoJson.toByteArray(Charsets.UTF_8),
                userId.toByteArray(Charsets.UTF_8)
            )

            val isValid = Ed25519Util.verify(publicKey, signatureData, signatureBase64)
            if (!isValid) {
                log.warn("Invalid signature for user '{}'", username)
                return@transaction VerifyResult.InvalidSignature
            }

            // 4. 计算设备指纹
            val deviceId = Sha256Util.hashHex(userId + deviceInfoJson)

            // 5. 注册/更新设备指纹
            val now = System.currentTimeMillis()
            val existingDevice = TrustDeviceTable.selectAll().where {
                (TrustDeviceTable.userId eq userId) and (TrustDeviceTable.deviceId eq deviceId)
            }.singleOrNull()

            if (existingDevice != null) {
                TrustDeviceTable.update({ (TrustDeviceTable.userId eq userId) and (TrustDeviceTable.deviceId eq deviceId) }) {
                    it[TrustDeviceTable.lastSeen] = now
                    it[TrustDeviceTable.deviceInfo] = deviceInfoJson
                }
            } else {
                TrustDeviceTable.insert {
                    it[TrustDeviceTable.userId] = userId
                    it[TrustDeviceTable.deviceId] = deviceId
                    it[TrustDeviceTable.deviceInfo] = deviceInfoJson
                    it[TrustDeviceTable.firstSeen] = now
                    it[TrustDeviceTable.lastSeen] = now
                    it[TrustDeviceTable.isTrusted] = 1
                }
            }

            // 6. 生成 session 和 tokens
            val sessionId = generateSessionId()
            val refreshToken = generateRefreshToken()
            val refreshTokenHash = Sha256Util.hashHex(refreshToken)

            // TODO: 不使用的字段，计划弃用，验证后去除
//            val refreshExpiresAt = now + jwtIssuer.accessTokenTtlSeconds * 1000 // 复用 JWT 配置中的 refresh TTL
            // 实际应使用 refreshTokenTtlSeconds，这里简化处理
            val actualRefreshTtl = 1_209_600_000L // 14 天

            // 同一用户 + 同一设备 → 更新已有 session，避免数据无限增长
            val existingSession = UserSessionTable.selectAll().where {
                (UserSessionTable.userId eq userId) and (UserSessionTable.deviceId eq deviceId) and (UserSessionTable.revoked eq 0)
            }.singleOrNull()

            if (existingSession != null) {
                UserSessionTable.update({ (UserSessionTable.userId eq userId) and (UserSessionTable.deviceId eq deviceId) and (UserSessionTable.revoked eq 0) }) {
                    it[UserSessionTable.sessionId] = sessionId
                    it[UserSessionTable.refreshTokenHash] = refreshTokenHash
                    it[UserSessionTable.createdAt] = now
                    it[UserSessionTable.expiresAt] = now + actualRefreshTtl
                    it[UserSessionTable.lastUsedAt] = now
                }
                log.debug("Updated existing session for userId={}, deviceId={}", userId, deviceId)
            } else {
                UserSessionTable.insert {
                    it[UserSessionTable.sessionId] = sessionId
                    it[UserSessionTable.userId] = userId
                    it[UserSessionTable.refreshTokenHash] = refreshTokenHash
                    it[UserSessionTable.deviceId] = deviceId
                    it[UserSessionTable.createdAt] = now
                    it[UserSessionTable.expiresAt] = now + actualRefreshTtl
                    it[UserSessionTable.lastUsedAt] = now
                    it[UserSessionTable.revoked] = 0
                }
            }

            // 7. 签发 JWT
            val accessToken = jwtIssuer.issue(
                userId = userId,
                username = username,
                sessionId = sessionId,
                deviceId = deviceId,
                globalRole = globalRole
            )

            // 8. 更新用户最后登录时间
            UserTable.update({ UserTable.userId eq userId }) {
                it[UserTable.lastLogin] = now
            }

            log.info("User '{}' logged in: userId={}, sessionId={}, deviceId={}", username, userId, sessionId, deviceId)

            VerifyResult.Success(
                accessToken = accessToken,
                refreshToken = refreshToken,
                expiresIn = jwtIssuer.accessTokenTtlSeconds,
                refreshExpiresIn = actualRefreshTtl / 1000,
                userId = userId,
                username = username,
                globalRole = globalRole
            )
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Token 刷新
    // ═══════════════════════════════════════════════════════════

    /**
     * 使用 refresh token 换发新的 access token。
     *
     * @param refreshToken 客户端持有的 refresh token 原文
     * @return 新的 access token，或错误
     */
    fun refreshAccessToken(refreshToken: String): RefreshResult {
        val refreshTokenHash = Sha256Util.hashHex(refreshToken)

        return transaction {
            val sessionRow = UserSessionTable.selectAll().where {
                UserSessionTable.refreshTokenHash eq refreshTokenHash
            }.singleOrNull()
                ?: return@transaction RefreshResult.InvalidToken

            if (sessionRow[UserSessionTable.revoked] == 1) {
                return@transaction RefreshResult.TokenRevoked
            }

            val now = System.currentTimeMillis()
            if (now > sessionRow[UserSessionTable.expiresAt]) {
                return@transaction RefreshResult.TokenExpired
            }

            val userId = sessionRow[UserSessionTable.userId]
            val sessionId = sessionRow[UserSessionTable.sessionId]
            val deviceId = sessionRow[UserSessionTable.deviceId]

            // 查用户信息
            val userRow = UserTable.selectAll().where { UserTable.userId eq userId }.singleOrNull()
                ?: return@transaction RefreshResult.UserNotFound

            val username = userRow[UserTable.username]
            val globalRole = userRow[UserTable.globalRole]

            // 签发新 JWT
            val accessToken = jwtIssuer.issue(
                userId = userId,
                username = username,
                sessionId = sessionId,
                deviceId = deviceId,
                globalRole = globalRole
            )

            // 更新会话最后使用时间 + 滑动过期
            UserSessionTable.update({ UserSessionTable.sessionId eq sessionId }) {
                it[UserSessionTable.lastUsedAt] = now
                it[UserSessionTable.expiresAt] = now + 1_209_600_000L // 14 天滑动
            }

            log.debug("Access token refreshed for userId={}, sessionId={}", userId, sessionId)

            RefreshResult.Success(
                accessToken = accessToken,
                expiresIn = jwtIssuer.accessTokenTtlSeconds
            )
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 会话撤销
    // ═══════════════════════════════════════════════════════════

    /**
     * 撤销用户会话（强制下线）。
     *
     * @param userId 目标用户 ID
     * @param sessionId 要撤销的会话 ID，null 则撤销该用户所有会话
     * @param operatorId 操作者 ID（用于权限日志）
     * @return 受影响的会话数
     */
    fun revokeSession(userId: String, sessionId: String? = null, operatorId: String? = null): Int {
        return transaction {
            val updateOp = if (sessionId != null) {
                UserSessionTable.update({
                    (UserSessionTable.userId eq userId) and (UserSessionTable.sessionId eq sessionId)
                }) {
                    it[UserSessionTable.revoked] = 1
                }
            } else {
                UserSessionTable.update({
                    UserSessionTable.userId eq userId
                }) {
                    it[UserSessionTable.revoked] = 1
                }
            }

            log.info("Session revoked: userId={}, sessionId={}, by={}, affected={}", userId, sessionId, operatorId, updateOp)
            updateOp
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 查询接口
    // ═══════════════════════════════════════════════════════════

    /**
     * 按用户名获取用户信息。
     */
    /**
     * 按 userId 获取用户的 avatar_url。
     * 返回 null 表示用户不存在。
     */
    fun getUserAvatarUrl(userId: String): String? {
        return transaction {
            UserTable.selectAll().where { UserTable.userId eq userId }
                .singleOrNull()?.get(UserTable.avatarUrl)
        }
    }

    //TODO: 可能需要增加函数 getUserByUserId()
    @Deprecated("Now use getUserByUserId() instead")
    fun getUserByUsername(username: String): UserInfo? {
        return transaction {
            UserTable.selectAll().where { UserTable.username eq username }.singleOrNull()?.let {
                UserInfo(
                    userId = it[UserTable.userId],
                    username = it[UserTable.username],
                    displayName = it[UserTable.displayName],
                    globalRole = it[UserTable.globalRole],
                    isActive = it[UserTable.isActive] == 1
                )
            }
        }
    }


    /**
     * 更新用户头像 URL。
     *
     * @param userId 用户 ID
     * @param avatarUrl 头像 URL 或本地存储路径
     * @return 操作结果
     */
    fun updateAvatarUrl(userId: String, avatarUrl: String): UpdateAvatarUrlResult {
        return transaction {
            val updated = UserTable.update({ UserTable.userId eq userId }) {
                it[UserTable.avatarUrl] = avatarUrl
            }
            if (updated > 0) {
                log.info("Avatar URL updated: userId={}, url={}", userId, avatarUrl)
                UpdateAvatarUrlResult.Success(avatarUrl)
            } else {
                UpdateAvatarUrlResult.UserNotFound
            }
        }
    }


    /**
     * 修改用户 username（昵称）。
     *
     * @param userId 当前用户 ID
     * @param newUsername 新昵称（最大 25 字符）
     * @return 操作结果
     */
    fun renameUsername(userId: String, newUsername: String): RenameUsernameResult {
        if (newUsername.length > 25) {
            return RenameUsernameResult.UsernameTooLong
        }
        return transaction {
            val updated = UserTable.update({ UserTable.userId eq userId }) {
                it[UserTable.username] = newUsername
            }
            if (updated > 0) {
                log.info("Username renamed for userId={} to {}", userId, newUsername)
                RenameUsernameResult.Success(newUsername)
            } else {
                RenameUsernameResult.UserNotFound
            }
        }
    }


    // ═══════════════════════════════════════════════════════════
    // 私有工具
    // ═══════════════════════════════════════════════════════════

    private fun generateSessionId(): String = "s_" + UUID.randomUUID().toString().replace("-", "").take(24)

    private fun generateRefreshToken(): String {
        val bytes = ByteArray(32).also { random.nextBytes(it) }
        return "rt_" + bytes.joinToString("") { "%02x".format(it) }
    }

    // ═══════════════════════════════════════════════════════════
    // PublicAdmin 管理（D2）
    // ═══════════════════════════════════════════════════════════

    fun appointPublicAdmin(ownerId: String, targetUserId: String): PublicAdminResult {
        return transaction {
            val ownerRole = UserTable.selectAll().where { UserTable.userId eq ownerId }
                .singleOrNull()?.get(UserTable.globalRole)
            if (ownerRole != "owner") return@transaction PublicAdminResult.NotOwner
            UserTable.selectAll().where { UserTable.userId eq targetUserId }.singleOrNull()
                ?: return@transaction PublicAdminResult.UserNotFound
            UserTable.update({ UserTable.userId eq targetUserId }) { it[globalRole] = "public_admin" }
            val exists = GlobalRoleTable.selectAll().where { GlobalRoleTable.userId eq targetUserId }.count()
            if (exists > 0L)
                GlobalRoleTable.update({ GlobalRoleTable.userId eq targetUserId }) { it[role] = "public_admin"; it[grantedBy] = ownerId; it[grantedAt] = System.currentTimeMillis() }
            else
                GlobalRoleTable.insert { it[GlobalRoleTable.userId] = targetUserId; it[GlobalRoleTable.role] = "public_admin"; it[GlobalRoleTable.grantedBy] = ownerId; it[GlobalRoleTable.grantedAt] = System.currentTimeMillis() }
            log.info("PublicAdmin appointed: userId={}, by={}", targetUserId, ownerId)
            PublicAdminResult.Success(targetUserId)
        }
    }

    fun removePublicAdmin(ownerId: String, targetUserId: String): PublicAdminResult {
        return transaction {
            val ownerRole = UserTable.selectAll().where { UserTable.userId eq ownerId }
                .singleOrNull()?.get(UserTable.globalRole)
            if (ownerRole != "owner") return@transaction PublicAdminResult.NotOwner
            UserTable.update({ UserTable.userId eq targetUserId }) { it[globalRole] = "member" }
            GlobalRoleTable.deleteWhere { (GlobalRoleTable.userId eq targetUserId) and (GlobalRoleTable.role eq "public_admin") }
            log.info("PublicAdmin removed: userId={}, by={}", targetUserId, ownerId)
            PublicAdminResult.Success(targetUserId)
        }
    }

    fun listPublicAdmins(): List<UserInfo> {
        return transaction {
            UserTable.selectAll().where { UserTable.globalRole eq "public_admin" }.map {
                UserInfo(it[UserTable.userId], it[UserTable.username], it[UserTable.displayName], it[UserTable.globalRole], it[UserTable.isActive] == 1)
            }
        }
    }


    // ═══════════════════════════════════════════════════════════
    // 用户管理: 停用/激活/驱逐
    // ═══════════════════════════════════════════════════════════

    /**
     * 停用用户（is_active = 0 + 吊销所有 session）。仅 Owner/PublicAdmin 可操作。
     */
    fun deactivateUser(targetUserId: String): Boolean {
        return transaction {
            val updated = UserTable.update({ UserTable.userId eq targetUserId }) {
                it[isActive] = 0
            }
            if (updated > 0) {
                // 吊销所有 session
                UserSessionTable.update({ UserSessionTable.userId eq targetUserId }) {
                    it[revoked] = 1
                }
                log.info("User deactivated: userId={}", targetUserId)
                true
            } else false
        }
    }

    /**
     * 激活用户（is_active = 1）。仅 Owner/PublicAdmin 可操作。
     */
    fun activateUser(targetUserId: String): Boolean {
        return transaction {
            UserTable.update({ UserTable.userId eq targetUserId }) {
                it[isActive] = 1
            } > 0
        }
    }

    /**
     * 从 PUBLIC 房间驱逐用户 — 级联删除所有资料（保留文件系统中的文件）。
     * 仅 Owner/PublicAdmin 可操作。
     */
    fun banUserFromPublic(targetUserId: String): BanUserResult {
        return transaction {
            UserTable.selectAll().where { UserTable.userId eq targetUserId }.singleOrNull()
                ?: return@transaction BanUserResult.UserNotFound

            // 1. 删除此人创建的所有房间（含关联成员/邀请/申请/文件）
            val createdRooms = RoomTable.selectAll().where { RoomTable.creatorId eq targetUserId }.toList()
            createdRooms.forEach { room ->
                val roomId = room[RoomTable.roomId]
                RoomFileTable.deleteWhere { RoomFileTable.roomId eq roomId }
                RoomMemberTable.deleteWhere { RoomMemberTable.roomId eq roomId }
                RoomInviteTable.deleteWhere { RoomInviteTable.roomId eq roomId }
                RoomJoinRequestTable.deleteWhere { RoomJoinRequestTable.roomId eq roomId }
                RoomTable.deleteWhere { RoomTable.roomId eq roomId }
            }

            // 2. 全局角色
            GlobalRoleTable.deleteWhere { GlobalRoleTable.userId eq targetUserId }

            // 3. 所有房间成员关系
            RoomMemberTable.deleteWhere { RoomMemberTable.userId eq targetUserId }

            // 4. 邀请/申请
            RoomInviteTable.deleteWhere { RoomInviteTable.inviterId eq targetUserId }
            RoomInviteTable.deleteWhere { RoomInviteTable.inviteeId eq targetUserId }
            RoomJoinRequestTable.deleteWhere { RoomJoinRequestTable.applicantId eq targetUserId }

            // 5. Session
            UserSessionTable.deleteWhere { UserSessionTable.userId eq targetUserId }

            // 6. 受信设备
            TrustDeviceTable.deleteWhere { TrustDeviceTable.userId eq targetUserId }

            // 7. 删除用户
            UserTable.deleteWhere { UserTable.userId eq targetUserId }

            log.warn("User banned from PUBLIC: userId={}, deletedRooms={}", targetUserId, createdRooms.size)
            BanUserResult.Success(createdRooms.size)
        }
    }


    companion object {
        private val log = LoggerFactory.getLogger(AuthService::class.java)
    }
}
