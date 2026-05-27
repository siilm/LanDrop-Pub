# LanDrop API 接入指南 — 房间 · 生命周期

> **版本**: v1.5  
> **最后更新**: 2026-05-24  
> **基础 URL**: `http://<host>:<port>`（默认 `http://0.0.0.0:8080`）

> 本文档从 `Deploy/API_Impls/rooms.md` 拆分而来。房间 API 完整索引见 [README.md](README.md)。

---

## 房间生命周期

### 4.1 我的房间

```
GET /api/rooms/mine
Authorization: Bearer <access_token>
```

**响应：**

```json
// 200 OK
{
  "count": "1",
  "rooms": [
    {
      "room_id": "PUBLIC",
      "name": "Public Room",
      "member_count": "5",
      "has_password": false
    }
  ]
}
```

**说明：** 仅返回当前用户已加入的房间。

---

### 4.2 全部房间（管理员视图）

仅 PublicAdmin 或 Owner 角色可访问。

```
GET /api/rooms/all
Authorization: Bearer <access_token>
```

**响应：**

```json
// 200 OK
{
  "count": "3",
  "rooms": [
    { "room_id": "PUBLIC", "name": "Public Room", "member_count": "5", "has_password": false },
    { "room_id": "A1B2C3", "name": "My Room", "member_count": "2", "has_password": true }
  ]
}

// 403 Forbidden
{ "error": "admin_required" }
```

---

### 4.3 创建房间

```
POST /api/rooms/create
Authorization: Bearer <access_token>
Content-Type: application/json
```

**请求体：**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| name | string | 是 | 房间名称 |
| password | string | 否 | 房间密码（SHA-256 存储） |
| room_type | int | 否 | 房间类型，默认 1 |

**响应：**

```json
// 201 Created
{
  "room_id": "A1B2C3",
  "name": "My Room"
}

// 403 Forbidden
{
  "error": "quota_exceeded",
  "message": "每个用户最多创建 2 个房间"
}
```

---

### 4.5 加入房间（待明确）

```
POST /api/rooms/{roomId}/join
Authorization: Bearer <access_token>
Content-Type: application/json
```

**请求体：**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| message | string | 否 | 加入申请消息 |

**响应：**

```json
// 200 OK
{ "status": "joined", "room_id": "A1B2C3" }

// 202 Accepted
{ "status": "pending" }

// 409 Conflict
{ "error": "already_member" }

// 404 Not Found
{ "error": "room_not_found" }
```

**说明：** 如果房间无需审批，直接加入（`joined`）；否则进入待审批状态（`pending`）。

- 创建人无法加入自己的房间，返回 `already_member`
- 申请人可查看自己的申请状态，见 4.5.1

---

### 4.5.1 查看我的加入申请

查询当前用户发起的、状态为待审批的所有加入申请。无需 roomId。

```
GET /api/rooms/join-requests/mine
Authorization: Bearer <access_token>
```

**响应：**

```json
// 200 OK
[
  {
    "id": "3",
    "room_id": "A1B2C3",
    "applicant_id": "Z9y8X7w6V5u4",
    "message": "请拉我进群",
    "applied_at": "1716000000000",
    "expires_at": "1716172800000"
  }
]
```

- `expires_at` 可空；到期后 CleanupJobs 自动清理
- 审批通过或拒绝后从列表中移除

---

### 4.6 强制加入房间

Owner 或 PublicAdmin 可强制将任意用户加入指定房间，跳过审批流程。

```
POST /api/rooms/{roomId}/force-join
Authorization: Bearer <access_token>
Content-Type: application/json
```

**请求体：**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| user_id | string | 是 | 要加入的目标用户 ID |

**响应：**

```json
// 200 OK
{ "status": "joined" }

// 400 Bad Request
{ "error": "roomId and user_id required" }

// 403 Forbidden
{ "error": "owner_or_public_admin_required" }

// 404 Not Found
{ "error": "room_not_found_or_inactive" }
```

**说明：**
- 鉴权要求 JWT 中 `global_role` 为 `owner` 或 `public_admin`
- 目标用户若已在房间中则静默成功（幂等）
- 加入角色自动根据全局权限设定：owner/public_admin 视为 creater（role=2），普通用户为 member（role=0）

---

### 4.7 查看待审批加入申请

查看指定房间的待审批加入申请列表。仅房间管理员（role>=1）可查看。

```
GET /api/rooms/{roomId}/join-requests
Authorization: Bearer <access_token>
```

**响应：**

```json
// 200 OK
[
  {
    "id": "1",
    "room_id": "A1B2C3",
    "applicant_id": "user12345678",
    "message": "请让我加入",
    "applied_at": "1716567890123",
    "expires_at": "1716740690123"
  }
]
```

---

### 4.8 审批加入申请（通过）

```
PUT /api/rooms/{roomId}/join-requests/{requestId}/approve
Authorization: Bearer <access_token>
```

**响应：**

```json
// 200 OK
{ "status": "approved" }

// 403 Forbidden
{ "error": "forbidden" }

// 404 Not Found
{ "error": "request_not_found" }

// 409 Conflict
{ "error": "already_processed" }
```

---

### 4.9 审批加入申请（拒绝）

```
PUT /api/rooms/{roomId}/join-requests/{requestId}/reject
Authorization: Bearer <access_token>
```

**响应：**

```json
// 200 OK
{ "status": "rejected" }

// 403 Forbidden
{ "error": "forbidden" }

// 404 Not Found
{ "error": "request_not_found" }

// 409 Conflict
{ "error": "already_processed" }
```

---

### 4.16 解散房间

仅 Owner / PublicAdmin / 房间创建人（Creater）可操作。解散后房间状态变为 `dissolved`，成员无法再加入或操作该房间。

```
DELETE /api/rooms/{roomId}
Authorization: Bearer <access_token>
```

**响应：**

```json
// 200 OK
{ "status": "dissolved" }

// 403 Forbidden
{ "error": "forbidden" }

// 404 Not Found
{ "error": "room_not_found" }
```

---

## WebSocket 事件通知

### 新加入申请通知（服务端 → 房间管理员，join_request）

当普通用户申请加入私有房间时，服务端向房间所有管理员（role>=1）推送：

```json
{
  "type": "join_request",
  "event_id": "uuid-of-join-request-event",
  "room_id": "A1B2C3",
  "applicant_id": "Z9y8X7w6V5u4"
}
```

### 管理员确认收到（管理员 → 服务端，event_ack）

```json
{
  "type": "event_ack",
  "event_id": "uuid-of-join-request-event"
}
```

> 兼容别名：`join_request_ack` 也可使用，推荐统一使用 `event_ack`。

### 申请已被处理通知（服务端 → 其他管理员，join_request_handled）

当某位管理员审批/拒绝后，服务端向房间**其他管理员**推送：

```json
{
  "type": "join_request_handled",
  "room_id": "A1B2C3",
  "action": "approved",
  "by": "admin_user_id"
}
```

| action 值 | 含义 |
|-----------|------|
| `approved` | 申请已被通过 |
| `rejected` | 申请已被拒绝 |

### 重试机制

- 通知发送失败时事件进入重试队列（指数退避 30s→60s→120s，最多 3 次）
- 事件有效期 72 小时，超期自动清理
