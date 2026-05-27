# LanDrop API 接入指南 — 房间 · 消息

> **版本**: v1.4  
> **最后更新**: 2026-05-21  
> **基础 URL**: `http://<host>:<port>`（默认 `http://0.0.0.0:8080`）

> 本文档从 `Deploy/API_Impls/rooms.md` 拆分而来。房间 API 完整索引见 [README.md](README.md)。

---

## 消息系统

### 4.7 房间消息

```
GET /api/rooms/{roomId}/messages?before=<timestamp>&limit=<n>
Authorization: Bearer <access_token>
```

**查询参数：**

| 参数 | 类型 | 默认 | 说明 |
|------|------|------|------|
| before | int64 | 当前时间 | 获取此时间戳之前的消息（分页游标） |
| limit | int | 50 | 每页条数 |

**响应：**

```json
// 200 OK
{
  "room_id": "PUBLIC",
  "count": "10",
  "messages": [
    {
      "message_id": "msg_abc123",
      "from": "u_abc12345",
      "elements": [{"type":"text","content":"Hello!"}],
      "status": "sent",
      "created_at": "1716000000000"
    }
  ]
}
```

---

### 4.8 编辑消息

编辑消息内容（elements JSON 数组）。需鉴权：消息发送者本人，或房间管理员/Owner。

```
PUT /api/messages/{messageId}
Authorization: Bearer <access_token>
Content-Type: application/json
```

**请求体：**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| elements | string | 是 | 新的消息元素 JSON 数组字符串 |

**响应：**

```json
// 200 OK
{ "status": "edited" }

// 403 Forbidden
{ "error": "forbidden" }

// 404 Not Found
{ "error": "not_found_or_not_owner" }
```

---

### 4.9 撤回/删除消息

撤回或删除消息**仅支持 WebSocket**，不再提供 HTTP 端点。服务端撤回后会主动广播通知给房间内其他成员。

**WebSocket 请求（客户端→服务端）：**

```json
{
  "type": "chat_recall",
  "message_id": "msg_abc123",
  "room_id": "PUBLIC"
}
```

**WebSocket 广播通知（服务端→房间成员）：**

```json
{
  "type": "chat_recall",
  "message_id": "msg_abc123",
  "room_id": "PUBLIC"
}
```

**鉴权：** 操作者权限等级 ≥ 消息发送者权限等级（owner > public_admin = creater > admin > member）。

**说明：** 撤回后 `chat_messages.status` 变为 `recalled`，`deleted_at` 记录时间。CleanupJobs 将在到期后自动清理。

---

### 4.19 发布公告

权限等级 > 1（Admin 以上）可操作。公告通过 WebSocket 推送给房间内所有成员。

```
POST /api/rooms/{roomId}/announce
Authorization: Bearer <access_token>
Content-Type: application/json
```

**请求体：**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| content | string | 是 | 公告内容 |

**响应：**

```json
// 200 OK
{ "status": "announced" }

// 403 Forbidden
{ "error": "forbidden" }
```
