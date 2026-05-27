package ink.siilm.core.room

import ink.siilm.core.event.EventService
import ink.siilm.core.persistence.*
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory

/**
 * 房间加入/邀请/审批服务（B2）。
 */
class RoomJoinService(private val roomManager: RoomManager) {

    /**
     * 申请加入房间。
     * 公开房间自动加入，私有房间创建 join_request。
     */
    fun joinRoom(roomId: String, userId: String, message: String? = null): JoinResult {
        return transaction {
            val room = RoomTable.selectAll().where { RoomTable.roomId eq roomId }.singleOrNull()
                ?: return@transaction JoinResult.RoomNotFound

            if (room[RoomTable.status] != RoomManager.STATUS_ACTIVE)
                return@transaction JoinResult.RoomDissolved

            // 检查是否已是成员
            val alreadyMember = RoomMemberTable.selectAll().where {
                (RoomMemberTable.roomId eq roomId) and (RoomMemberTable.userId eq userId)
            }.count() > 0
            if (alreadyMember) return@transaction JoinResult.AlreadyMember

            val roomType = room[RoomTable.roomType]

            // Owner/PublicAdmin 可强制加入任意房间（不经过审批）
            val globalRole = UserTable.selectAll().where { UserTable.userId eq userId }
                .singleOrNull()?.get(UserTable.globalRole)
            val isPrivileged = globalRole == "owner" || globalRole == "public_admin"

            if (roomType == RoomManager.ROOM_TYPE_MEMBER_PUBLIC || isPrivileged) {
                // 公开房间 OR 特权用户：自动加入
                roomManager.joinRoom(roomId, userId)
                JoinResult.Joined
            } else {
                // 私有房间 + 普通用户：创建申请
                val existingReq = RoomJoinRequestTable.selectAll().where {
                    (RoomJoinRequestTable.roomId eq roomId) and
                    (RoomJoinRequestTable.applicantId eq userId) and
                    (RoomJoinRequestTable.status eq 0)
                }.count()
                if (existingReq > 0) return@transaction JoinResult.RequestAlreadyPending

                val now = System.currentTimeMillis()
                RoomJoinRequestTable.insert {
                    it[RoomJoinRequestTable.roomId] = roomId
                    it[RoomJoinRequestTable.applicantId] = userId
                    it[RoomJoinRequestTable.status] = 0
                    it[RoomJoinRequestTable.message] = message
                    it[RoomJoinRequestTable.appliedAt] = now
                    it[RoomJoinRequestTable.expiresAt] = now + 48 * 60 * 60 * 1000L // 48小时
                }
                // 创建事件记录（通知房间管理员）
                val eventId = java.util.UUID.randomUUID().toString()
                EventTable.insert {
                    it[EventTable.eventId] = eventId
                    it[EventTable.type] = "join_request"
                    it[EventTable.initiatorId] = userId
                    it[EventTable.targetRoom] = roomId
                    it[EventTable.payload] = message?.let { msg -> """{"message":"$msg"}""" }
                    it[EventTable.status] = EventService.STATUS_PENDING
                    it[EventTable.createdAt] = now
                    it[EventTable.expiresAt] = now + 72 * 60 * 60 * 1000L // 72h
                }
                log.info("Join request created: roomId={}, userId={}", roomId, userId)
                JoinResult.Pending(eventId)
            }
        }
    }

    /**
     * 邀请用户进入房间。
     */
    fun inviteToRoom(roomId: String, inviterId: String, inviteeId: String): InviteResult {
        return transaction {
            RoomTable.selectAll().where { RoomTable.roomId eq roomId }.singleOrNull()
                ?: return@transaction InviteResult.RoomNotFound

            // 检查邀请者是否是成员
            RoomMemberTable.selectAll().where {
                (RoomMemberTable.roomId eq roomId) and (RoomMemberTable.userId eq inviterId)
            }.singleOrNull() ?: return@transaction InviteResult.NotMember

            // 检查被邀请者是否已是成员
            val inviteeMember = RoomMemberTable.selectAll().where {
                (RoomMemberTable.roomId eq roomId) and (RoomMemberTable.userId eq inviteeId)
            }.count()
            if (inviteeMember > 0) return@transaction InviteResult.AlreadyMember

            // 检查是否已有待审批邀请
            val existingInvite = RoomInviteTable.selectAll().where {
                (RoomInviteTable.roomId eq roomId) and
                (RoomInviteTable.inviteeId eq inviteeId) and
                (RoomInviteTable.status eq 0)
            }.count()
            if (existingInvite > 0) return@transaction InviteResult.AlreadyInvited

            val now = System.currentTimeMillis()
            RoomInviteTable.insert {
                it[RoomInviteTable.roomId] = roomId
                it[RoomInviteTable.inviterId] = inviterId
                it[RoomInviteTable.inviteeId] = inviteeId
                it[RoomInviteTable.status] = 0
                it[RoomInviteTable.requestedAt] = now
                it[RoomInviteTable.expiresAt] = now + 48 * 60 * 60 * 1000L
            }
            // 创建事件记录（通知受邀人）
            val eventId = java.util.UUID.randomUUID().toString()
            EventTable.insert {
                it[EventTable.eventId] = eventId
                it[EventTable.type] = "invite"
                it[EventTable.initiatorId] = inviterId
                it[EventTable.targetRoom] = roomId
                it[EventTable.targetUser] = inviteeId
                it[EventTable.status] = EventService.STATUS_PENDING
                it[EventTable.retryCount] = 0
                it[EventTable.maxRetries] = 3
                it[EventTable.createdAt] = now
                it[EventTable.expiresAt] = now + 72 * 60 * 60 * 1000L
            }
            log.info("Invite created: roomId={}, inviter={}, invitee={}", roomId, inviterId, inviteeId)
            InviteResult.Success(eventId)
        }
    }


    fun getMyInvites(userId: String): List<InviteInfo> {
        return transaction {
            RoomInviteTable.selectAll().where {
                (RoomInviteTable.inviterId eq userId) and (RoomInviteTable.status eq 0)
            }.map {
                InviteInfo(
                    id = it[RoomInviteTable.id],
                    roomId = it[RoomInviteTable.roomId],
                    inviterId = it[RoomInviteTable.inviterId],
                    inviteeId = it[RoomInviteTable.inviteeId],
                    requestedAt = it[RoomInviteTable.requestedAt],
                    expiresAt = it[RoomInviteTable.expiresAt]
                )
            }
        }
    }

    /**
     * 获取当前用户收到的所有待审批邀请。
     */
    fun getMyReceivedInvites(userId: String): List<InviteInfo> {
        return transaction {
            RoomInviteTable.selectAll().where {
                (RoomInviteTable.inviteeId eq userId) and (RoomInviteTable.status eq 0)
            }.map {
                InviteInfo(
                    id = it[RoomInviteTable.id],
                    roomId = it[RoomInviteTable.roomId],
                    inviterId = it[RoomInviteTable.inviterId],
                    inviteeId = it[RoomInviteTable.inviteeId],
                    requestedAt = it[RoomInviteTable.requestedAt],
                    expiresAt = it[RoomInviteTable.expiresAt]
                )
            }
        }
    }

    /**
     * 获取单个邀请信息。
     */
    fun getInvite(roomId: String, inviteId: Int): InviteInfo? {
        return transaction {
            RoomInviteTable.selectAll().where {
                (RoomInviteTable.roomId eq roomId) and (RoomInviteTable.id eq inviteId)
            }.map {
                InviteInfo(
                    id = it[RoomInviteTable.id],
                    roomId = it[RoomInviteTable.roomId],
                    inviterId = it[RoomInviteTable.inviterId],
                    inviteeId = it[RoomInviteTable.inviteeId],
                    requestedAt = it[RoomInviteTable.requestedAt],
                    expiresAt = it[RoomInviteTable.expiresAt]
                )
            }.singleOrNull()
        }
    }

    /**
     * 审批加入申请。
     */
    fun approveJoin(roomId: String, requestId: Int, reviewerId: String): ApproveResult {
        return transaction {
            val request = RoomJoinRequestTable.selectAll().where {
                (RoomJoinRequestTable.id eq requestId) and (RoomJoinRequestTable.roomId eq roomId)
            }.singleOrNull() ?: return@transaction ApproveResult.RequestNotFound

            if (request[RoomJoinRequestTable.status] != 0)
                return@transaction ApproveResult.AlreadyProcessed

            val applicantId = request[RoomJoinRequestTable.applicantId]

            RoomJoinRequestTable.update({ RoomJoinRequestTable.id eq requestId }) {
                it[status] = 1
                it[reviewedBy] = reviewerId
                it[reviewedAt] = System.currentTimeMillis()
            }

            roomManager.joinRoom(roomId, applicantId)
            // 更新对应事件为已确认
            EventTable.update({
                (EventTable.type eq "join_request") and
                (EventTable.targetRoom eq roomId) and
                (EventTable.initiatorId eq applicantId) and
                (EventTable.status eq EventService.STATUS_PENDING)
            }) {
                it[status] = EventService.STATUS_CONFIRMED
                it[confirmedAt] = System.currentTimeMillis()
            }
            log.info("Join approved: roomId={}, userId={}, by={}", roomId, applicantId, reviewerId)
            ApproveResult.Success
        }
    }

    /**
     * 拒绝加入申请。
     */
    fun rejectJoin(roomId: String, requestId: Int, reviewerId: String): ApproveResult {
        return transaction {
            val request = RoomJoinRequestTable.selectAll().where {
                (RoomJoinRequestTable.id eq requestId) and (RoomJoinRequestTable.roomId eq roomId)
            }.singleOrNull() ?: return@transaction ApproveResult.RequestNotFound

            if (request[RoomJoinRequestTable.status] != 0)
                return@transaction ApproveResult.AlreadyProcessed

            val applicantId = request[RoomJoinRequestTable.applicantId]

            RoomJoinRequestTable.update({
                (RoomJoinRequestTable.id eq requestId) and (RoomJoinRequestTable.status eq 0)
            }) {
                it[status] = 2
                it[reviewedBy] = reviewerId
                it[reviewedAt] = System.currentTimeMillis()
            }

            // 更新对应事件为已拒绝
            EventTable.update({
                (EventTable.type eq "join_request") and
                (EventTable.targetRoom eq roomId) and
                (EventTable.initiatorId eq applicantId) and
                (EventTable.status eq EventService.STATUS_PENDING)
            }) {
                it[status] = EventService.STATUS_REJECTED
                it[confirmedAt] = System.currentTimeMillis()
            }

            ApproveResult.Success
        }
    }

    /**
     * 审批邀请（被邀请者接受或拒绝）。
     */
    fun approveInvite(roomId: String, inviteId: Int, reviewerId: String): ApproveResult {
        return transaction {
            val invite = RoomInviteTable.selectAll().where {
                (RoomInviteTable.id eq inviteId) and (RoomInviteTable.roomId eq roomId)
            }.singleOrNull() ?: return@transaction ApproveResult.RequestNotFound

            if (invite[RoomInviteTable.status] != 0)
                return@transaction ApproveResult.AlreadyProcessed

            // 受邀人同意 → 创建 join_request 通知管理员审批
            val inviteeId = invite[RoomInviteTable.inviteeId]
            val now = System.currentTimeMillis()
            RoomInviteTable.update({ RoomInviteTable.id eq inviteId }) {
                it[status] = 1
                it[reviewedBy] = reviewerId
                it[reviewedAt] = now
            }
            RoomJoinRequestTable.insert {
                it[RoomJoinRequestTable.roomId] = roomId
                it[RoomJoinRequestTable.applicantId] = inviteeId
                it[RoomJoinRequestTable.status] = 0
                it[RoomJoinRequestTable.message] = "invite_accepted"
                it[RoomJoinRequestTable.appliedAt] = now
                it[RoomJoinRequestTable.expiresAt] = now + 72 * 60 * 60 * 1000L
            }
            val eventId = java.util.UUID.randomUUID().toString()
            EventTable.insert {
                it[EventTable.eventId] = eventId
                it[EventTable.type] = "join_request"
                it[EventTable.initiatorId] = inviteeId
                it[EventTable.targetRoom] = roomId
                it[EventTable.status] = EventService.STATUS_PENDING
                it[EventTable.retryCount] = 0
                it[EventTable.maxRetries] = 3
                it[EventTable.createdAt] = now
                it[EventTable.expiresAt] = now + 72 * 60 * 60 * 1000L
            }
            log.info("Invite accepted, referred to admin: roomId={}, userId={}", roomId, inviteeId)
            ApproveResult.ReferredToAdmin(eventId)
        }
    }

    fun rejectInvite(roomId: String, inviteId: Int, reviewerId: String): ApproveResult {
        return transaction {
            val invite = RoomInviteTable.selectAll().where {
                (RoomInviteTable.id eq inviteId) and (RoomInviteTable.roomId eq roomId)
            }.singleOrNull() ?: return@transaction ApproveResult.RequestNotFound

            if (invite[RoomInviteTable.status] != 0)
                return@transaction ApproveResult.AlreadyProcessed

            val inviteeId = invite[RoomInviteTable.inviteeId]

            RoomInviteTable.update({
                (RoomInviteTable.id eq inviteId) and (RoomInviteTable.status eq 0)
            }) {
                it[status] = 2
                it[reviewedBy] = reviewerId
                it[reviewedAt] = System.currentTimeMillis()
            }

            // 更新对应事件为已拒绝
            EventTable.update({
                (EventTable.type eq "invite") and
                (EventTable.targetRoom eq roomId) and
                (EventTable.targetUser eq inviteeId) and
                (EventTable.status eq EventService.STATUS_PENDING)
            }) {
                it[status] = EventService.STATUS_REJECTED
                it[confirmedAt] = System.currentTimeMillis()
            }

            ApproveResult.Success
        }
    }

    /**
     * 获取待审批的加入申请列表。
     */
    fun getPendingJoinRequests(roomId: String): List<JoinRequestInfo> {
        return transaction {
            RoomJoinRequestTable.selectAll().where {
                (RoomJoinRequestTable.roomId eq roomId) and (RoomJoinRequestTable.status eq 0)
            }.map {
                JoinRequestInfo(
                    id = it[RoomJoinRequestTable.id],
                    roomId = it[RoomJoinRequestTable.roomId],
                    applicantId = it[RoomJoinRequestTable.applicantId],
                    message = it[RoomJoinRequestTable.message],
                    appliedAt = it[RoomJoinRequestTable.appliedAt],
                    expiresAt = it[RoomJoinRequestTable.expiresAt]
                )
            }
        }
    }

    /**
     * 获取指定用户的所有未处理加入申请。
     */
    fun getMyJoinRequests(userId: String): List<JoinRequestInfo> {
        return transaction {
            RoomJoinRequestTable.selectAll().where {
                (RoomJoinRequestTable.applicantId eq userId) and (RoomJoinRequestTable.status eq 0)
            }.map {
                JoinRequestInfo(
                    id = it[RoomJoinRequestTable.id],
                    roomId = it[RoomJoinRequestTable.roomId],
                    applicantId = it[RoomJoinRequestTable.applicantId],
                    message = it[RoomJoinRequestTable.message],
                    appliedAt = it[RoomJoinRequestTable.appliedAt],
                    expiresAt = it[RoomJoinRequestTable.expiresAt]
                )
            }
        }
    }

    /**
     * 获取待审批的邀请列表。
     */
    fun getPendingInvites(roomId: String): List<InviteInfo> {
        return transaction {
            RoomInviteTable.selectAll().where {
                (RoomInviteTable.roomId eq roomId) and (RoomInviteTable.status eq 0)
            }.map {
                InviteInfo(
                    id = it[RoomInviteTable.id],
                    roomId = it[RoomInviteTable.roomId],
                    inviterId = it[RoomInviteTable.inviterId],
                    inviteeId = it[RoomInviteTable.inviteeId],
                    requestedAt = it[RoomInviteTable.requestedAt],
                    expiresAt = it[RoomInviteTable.expiresAt]
                )
            }
        }
    }

    // ── 结果类型 ──────────────────────────────────────────




    companion object {
        private val log = LoggerFactory.getLogger(RoomJoinService::class.java)
    }
}
