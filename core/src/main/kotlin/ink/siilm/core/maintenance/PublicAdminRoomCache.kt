package ink.siilm.core.maintenance

import ink.siilm.core.persistence.GlobalRoleTable
import ink.siilm.core.persistence.PublicManagedRoomTable
import ink.siilm.core.persistence.RoomTable
import ink.siilm.core.room.RoomManager
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

/**
 * PublicAdmin 预计算表维护（B5）。
 * 缓存 PublicAdmin 可管辖的 Member 房间列表，避免每次权限判定时多表 JOIN。
 */
class PublicAdminRoomCache {

    /**
     * 全量刷新预计算表。
     * 查询所有 global_role=public_admin 的用户，收集所有 room_type=Member 的房间。
     */
    fun fullRefresh() {
        transaction {
            // 清空预计算表
            PublicManagedRoomTable.deleteAll()

            // 查询所有 public_admin 用户
            val publicAdminIds = GlobalRoleTable.selectAll()
                .where { GlobalRoleTable.role eq "public_admin" }
                .map { it[GlobalRoleTable.userId] }
                .toSet()

            if (publicAdminIds.isEmpty()) {
                log.debug("No public_admin users, skipping cache refresh")
                return@transaction
            }

            // 查询所有活跃的 Member 房间
            val memberRooms = RoomTable.selectAll().where {
                (RoomTable.roomType eq RoomManager.ROOM_TYPE_MEMBER_PRIVATE) or
                (RoomTable.roomType eq RoomManager.ROOM_TYPE_MEMBER_PUBLIC)
            }.andWhere { RoomTable.status eq RoomManager.STATUS_ACTIVE }

            val now = System.currentTimeMillis()
            val inserted = mutableSetOf<String>()

            for (room in memberRooms) {
                val roomId = room[RoomTable.roomId]
                if (roomId !in inserted) {
                    PublicManagedRoomTable.insert {
                        it[PublicManagedRoomTable.roomId] = roomId
                        it[PublicManagedRoomTable.addedAt] = now
                    }
                    inserted.add(roomId)
                }
            }

            log.info("PublicAdmin cache refreshed: {} rooms cached", inserted.size)
        }
    }

    /**
     * 房间创建时增量添加。
     */
    fun onRoomCreated(roomId: String, roomType: Int) {
        if (roomType == RoomManager.ROOM_TYPE_MEMBER_PRIVATE || roomType == RoomManager.ROOM_TYPE_MEMBER_PUBLIC) {
            transaction {
                val exists = PublicManagedRoomTable.selectAll()
                    .where { PublicManagedRoomTable.roomId eq roomId }.count()
                if (exists == 0L) {
                    PublicManagedRoomTable.insert {
                        it[PublicManagedRoomTable.roomId] = roomId
                        it[PublicManagedRoomTable.addedAt] = System.currentTimeMillis()
                    }
                    log.debug("PublicAdmin cache: added room {}", roomId)
                }
            }
        }
    }

    fun getManagedRoomIds(): Set<String> {
        return transaction {
            PublicManagedRoomTable.selectAll().map { it[PublicManagedRoomTable.roomId] }.toSet()
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(PublicAdminRoomCache::class.java)
    }
}
