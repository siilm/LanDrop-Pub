# LanDrop API 接入指南 — 房间 · 邀请系统

> **版本**: v1.6  
> **最后更新**: 2026-05-27  
> **基础 URL**: `http://<host>:<port>`（默认 `http://0.0.0.0:8080`）

> 本文档从 `Deploy/API_Impls/rooms.md` 拆分而来。房间 API 完整索引见 [README.md](README.md)。

---

## 邀请系统

### 4.20 邀请用户

房间成员可邀请其他用户加入房间。

**行为区分：**

| 邀请人角色 | 行为 |
|-----------|------|
| **Admin 及以上** (role >= 1) | 被邀请人直接加入房间，创建 events 记录（status=已确认） |
| **普通成员** (role == 0) | 创建待审批邀请记录，通过 WebSocket 通知受邀人 |

普通成员发出的邀请 48 小时后过期，服务端自动创建 events 记录。

```
POST /api/rooms/{roomId}/invite
Authorization: Bearer <access_token>
Content-Type: application/json
```

**请求体：**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| user_ids | string[] | 是 | 被邀请用户 ID 列表 |

**响应：**

```json
// 200 OK
{
  "invites": [
    { "user_id": "B2c3D4e5F6g7", "status": "joined" },
    { "user_id": "C3d4E5f6G7h8", "status": "ok" }
  ]
}
```

| status 值 | 说明 |
|-----------|------|
| `joined` | 邀请人权限足够，被邀请人已直接加入（admin 邀请） |
| `ok` | 邀请已创建，受邀人收到 WS 通知（member 邀请） |
| `room_not_found` | 房间不存在 |
| `inviter_not_member` | 邀请人不在该房间 |
| `already_member` | 受邀人已是房间成员 |
| `already_invited` | 已有待审批的邀请 |

---

### 4.21 待审批邀请列表（管理员视图）

Admin 级以上可查看房间内所有待审批（status=0）的邀请。

```
GET /api/rooms/{roomId}/invites
Authorization: Bearer <access_token>
```

**响应：**

```json
// 200 OK
[
  {
    "id": "1",
    "room_id": "PUBLIC",
    "inviter_id": "A1b2C3d4E5f6",
    "invitee_id": "B2c3D4e5F6g7",
    "requested_at": "1716000000000",
    "expires_at": "1716172800000"
  }
]
```

---

### 4.21.5 查看我发出的邀请（邀请人主动查询）

任何已登录用户均可查询自己发出的所有待审批邀请。**推荐邀请人通过此 API 主动查询审批结果**，无需等待实时推送。

```
GET /api/rooms/invites/mine
Authorization: Bearer <access_token>
```

**响应：**

```json
// 200 OK
[
  {
    "id": "3",
    "room_id": "A1B2C3",
    "inviter_id": "Z9y8X7w6V5u4",
    "invitee_id": "B2c3D4e5F6g7",
    "requested_at": "1716000000000",
    "expires_at": "1716172800000"
  }
]
```

> 空数组 `[]` 表示没有待审批的邀请（可能已被审批或自动过期）。

---

### 4.21.6 查看我收到的邀请（受邀人主动查询）

任何已登录用户均可查询所有发给自己的待审批邀请。

```
GET /api/rooms/invites/to-me
Authorization: Bearer <access_token>
```

**响应：**

```json
// 200 OK
[
  {
    "id": "5",
    "room_id": "A1B2C3",
    "inviter_id": "Z9y8X7w6V5u4",
    "invitee_id": "B2c3D4e5F6g7",
    "requested_at": "1716000000000",
    "expires_at": "1716172800000"
  }
]
```

---

### 4.22 同意邀请

Admin 级以上可审批通过邀请。被邀请人自动加入房间。

```
PUT /api/rooms/{roomId}/invites/{inviteId}/approve
Authorization: Bearer <access_token>
```

**响应：**

```json
// 200 OK
{ "status": "approved" }
// 404 Not Found
{ "error": "invite_not_found" }
// 409 Conflict
{ "error": "already_processed" }
```

---

### 4.23 拒绝邀请

Admin 级以上可拒绝邀请。

```
PUT /api/rooms/{roomId}/invites/{inviteId}/reject
Authorization: Bearer <access_token>
```

**响应：**

```json
// 200 OK
{ "status": "rejected" }
// 404 Not Found
{ "error": "invite_not_found" }
// 409 Conflict
{ "error": "already_processed" }
```

---

## WebSocket 事件通知

### 邀请通知（服务端 → 受邀人，仅 member 邀请时触发）

普通成员发出邀请后，服务端通过 WebSocket 向受邀人推送：

```json
{
  "type": "invite_notify",
  "event_id": "uuid-of-invite-event",
  "room_id": "A1B2C3",
  "inviter_id": "Z9y8X7w6V5u4"
}
```

> **注意**：admin+ 邀请时直接加入，不发送此通知。

### 受邀人确认收到（受邀人 → 服务端）

```json
{
  "type": "event_ack",
  "event_id": "uuid-of-invite-event"
}
```

> 兼容别名：`invite_ack` 也可使用，推荐统一使用 `event_ack`。

### 重试机制

- member 邀请时通知发送失败 → 事件进入重试队列（指数退避 30s→60s→120s，最多 3 次）
- admin 邀请的事件创建时即标记为已确认，不需要重试
- 事件有效期 72 小时，超期自动清理
