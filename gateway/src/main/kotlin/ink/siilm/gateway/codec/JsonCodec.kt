package ink.siilm.gateway.codec

import ink.siilm.gateway.model.ws.server.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

private val json2 = Json { explicitNulls = false; encodeDefaults = true }

/**
 * JSON 编解码工具。
 *
 * 封装 kotlinx.serialization Json 实例，提供客户端 JSON ↔ JsonObject 的解析
 * 以及各类出站 JSON 对象的构造便捷方法。
 *
 * 参照 [`gateway-implementation-plan.md`](../../../../LanDrop-Doc/gateway-implementation-plan.md) §6 步骤 8
 */
class JsonCodec(
    @PublishedApi internal val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }
) {
    /**
     * 解析原始 JSON 文本为 [JsonObject]。
     */
    fun parseToJsonObject(text: String): JsonObject {
        return json.decodeFromString<JsonObject>(text)
    }

    /**
     * 从 JsonObject 中安全提取字符串字段。
     *
     * @return 字段值，若不存在或类型不匹配返回 null
     */
    fun getString(obj: JsonObject, key: String): String? {
        return obj[key]?.jsonPrimitive?.contentOrNull
    }

    /**
     * 从 JsonObject 中安全提取长整数字段。
     *
     * @return 字段值，若不存在或类型不匹配返回 null
     */
    fun getLong(obj: JsonObject, key: String): Long? {
        return obj[key]?.jsonPrimitive?.longOrNull
    }

    /**
     * 从 JsonObject 中安全提取整数字段。
     */
    fun getInt(obj: JsonObject, key: String): Int? {
        return obj[key]?.jsonPrimitive?.intOrNull
    }

    /**
     * 从 JsonObject 中安全提取布尔字段。
     */
    fun getBoolean(obj: JsonObject, key: String): Boolean? {
        return obj[key]?.jsonPrimitive?.booleanOrNull
    }

    /**
     * 从 JsonObject 中安全提取嵌套 JsonObject 字段。
     */
    fun getObject(obj: JsonObject, key: String): JsonObject? {
        return obj[key]?.jsonObject
    }

    /**
     * 从 JsonObject 中安全提取 JsonArray 字段。
     */
    fun getArray(obj: JsonObject, key: String): JsonArray? {
        return obj[key]?.jsonArray
    }

    /**
     * 从 JsonObject 中提取 "type" 字段（消息类型标识）。
     */
    fun getType(obj: JsonObject): String? = getString(obj, "type")

    // ═══════════════════════════════════════════════════════════
    // 出站 JSON 构造便捷方法
    // ═══════════════════════════════════════════════════════════

    private val json1 = json2

    /**
     * 构造聊天消息的出站 JSON。
     *
     * ★ 安全关键: 不包含 storage_path，防止客户端获取服务器文件路径。
     */
    @Deprecated("Use ChatMessageOut data class + wsJson.encodeToString() instead")
    fun buildChatMessageJson(
        messageId: String,
        fromUserId: String,
        toUserId: String?,
        roomId: String?,
        content: String,
        timestamp: Long
    ): JsonObject = json.parseToJsonElement(
        json2.encodeToString(
            ChatMessageOut(
                messageId = messageId, from = fromUserId, displayName = fromUserId,
                content = content, to = toUserId, roomId = roomId, timestamp = timestamp
            )
        )
    ).jsonObject
    /* DEPRECATED buildJsonObject version:
    buildJsonObject {
        put("type", "chat_message")
        ...
    }
    */

    /**
     * 构造房间事件的出站 JSON（room_created / room_joined / room_left 等）。
     */
    @Deprecated("Use RoomEventOut data class + wsJson.encodeToString() instead")
    fun buildRoomEventJson(
        eventType: String,
        roomId: String,
        userId: String,
        extra: Map<String, String> = emptyMap()
    ): JsonObject = json.parseToJsonElement(
        json2.encodeToString(
            RoomEventOut(type = eventType, userId = userId, roomId = roomId)
        )
    ).jsonObject
    /* DEPRECATED buildJsonObject version:
    buildJsonObject { put("type", eventType); put("room_id", roomId); put("user_id", userId); ... }
    */

    /**
     * 构造错误消息的出站 JSON。
     */
    @Deprecated("Use ErrorOut data class + wsJson.encodeToString() instead")
    fun buildErrorJson(
        refMessageId: String?,
        errorCode: Int,
        errorMessage: String,
        errorType: String? = null
    ): JsonObject = json.parseToJsonElement(
        json2.encodeToString(
            ErrorOut(refMessageId = refMessageId, errorCode = errorCode, errorMessage = errorMessage, errorType = errorType)
        )
    ).jsonObject
    /* DEPRECATED buildJsonObject version */


    /**
     * 构造 PONG 心跳响应的出站 JSON。
     */
    @Deprecated("Use PongOut data class + wsJson.encodeToString() instead")
    fun buildPongJson(timestamp: Long): JsonObject = json.parseToJsonElement(
        json2.encodeToString(PongOut(timestamp = timestamp))
    ).jsonObject
    /* DEPRECATED buildJsonObject version */

    /**
     * 构造 ACK 确认消息的出站 JSON。
     */
    @Deprecated("Use AckOut data class + wsJson.encodeToString() instead")
    fun buildAckJson(messageId: String, status: String): JsonObject = json.parseToJsonElement(
        json2.encodeToString(AckOut(messageId = messageId, status = status))
    ).jsonObject
    /* DEPRECATED buildJsonObject version */

    /**
     * 构造文件传输指令的出站 JSON。
     * 不包含服务器内部路径。
     */
    @Deprecated("Use FileInstructionOut data class + wsJson.encodeToString() instead")
    fun buildFileInstructionJson(
        command: String,
        fileId: String,
        fileName: String,
        fileSize: Long
    ): JsonObject = json.parseToJsonElement(
        json2.encodeToString(
            FileInstructionOut(command = command, fileId = fileId)
        )
    ).jsonObject
    /* DEPRECATED buildJsonObject version */

    /**
     * 构造连接确认的出站 JSON。
     */
    @Deprecated("Use Connect edOut data class + wsJson.encodeToString() instead")
    fun buildConnectedJson(userId: String, sessionId: String): JsonObject = json.parseToJsonElement(
        json2.encodeToString(ConnectedOut(userId = userId, sessionId = sessionId))
    ).jsonObject
    /* DEPRECATED buildJsonObject version */
}