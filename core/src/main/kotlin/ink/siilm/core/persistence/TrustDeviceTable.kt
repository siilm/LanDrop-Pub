package ink.siilm.core.persistence

import org.jetbrains.exposed.sql.Table

/**
 * `trust_devices` 表 — 设备指纹。
 *
 * device_id = SHA-256(username + compact_json(device_info))
 *
 * 对应 SQL: database-design.md §3.3
 */
object TrustDeviceTable : Table("trust_devices") {
    val id = integer("id").autoIncrement()
    val userId = varchar("user_id", 64)
    val deviceId = varchar("device_id", 128)               // SHA-256 设备指纹
    val deviceInfo = text("device_info").nullable()        // 设备信息 JSON
    val firstSeen = long("first_seen")
    val lastSeen = long("last_seen")
    val isTrusted = integer("is_trusted").default(1)

    override val primaryKey = PrimaryKey(id)

    init {
        index("uq_trustdev_user_device", isUnique = true, userId, deviceId)
        index("idx_trustdev_user", isUnique = false, userId)
    }
}
