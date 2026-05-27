# LanDrop API 接入指南 — 房间 · 成员管理

> **版本**: v1.5  
> **最后更新**: 2026-05-21  
> **基础 URL**: `http://<host>:<port>`（默认 `http://0.0.0.0:8080`）

> 本文档从 `rooms.md` 拆分而来。完整 API 索引见 [INDEX.md](INDEX.md)。

---

## 成员管理 API

### 4.1 房间成员

```
GET /api/rooms/{roomId}/members
Authorization: Bearer <access_token>
```

**响应：**

```json
[
  {
    "user_id": "B2c3D4e5F6g7",
    "username": "alice",
    "display_name": "Alice",
    "role": 2,
    "muted": 0或1,
    "avatar_url": "/api/getfiles/avatar/B2c3D4e5F6g7.jpg",
    "joined_at": 1716249600000
  }
]
```

---

### 4.2 管理他人头衔

Admin 级以上可操作。

```
PUT /api/rooms/{roomId}/members/{userId}/nickname
Authorization: Bearer <access_token>
Content-Type: application/json
```

**说明：** 若 `targetUserId` 与操作者相同，自动降级为修改自己头衔（等同 4.6）。

---

### 4.3 踢出成员

```
DELETE /api/rooms/{roomId}/members/{userId}
Authorization: Bearer <access_token>
```

Admin 级以上可操作。

**响应：**

```json
// 200 OK
{ "status": "ok" }

// 403 Forbidden
{ "error": "forbidden" }
```

---

### 4.4 禁言/解除禁言

Admin 级以上可操作。被禁言成员的 role 变为 `-1`（相当于临时禁止发消息）。

```
PUT /api/rooms/{roomId}/members/{userId}/mute     # 禁言
DELETE /api/rooms/{roomId}/members/{userId}/mute   # 解除禁言
Authorization: Bearer <access_token>
```

**响应：**

```json
// 200 OK
{ "status": "ok", "muted": true }

// 200 OK（解除）
{ "status": "ok", "muted": false }

// 403 Forbidden
{ "error": "forbidden" }

// 404 Not Found
{ "error": "not_member" }
```

---

### 4.5 晋升/降级管理员 (v1.5)

```
PUT /api/rooms/{roomId}/members/{userId}/promote   # 晋升为管理员
PUT /api/rooms/{roomId}/members/{userId}/demote     # 降级为普通成员
Authorization: Bearer <access_token>
```

**权限要求：** 操作者需为房间 CREATER 或 ROLE_ADMIN

**响应：**

```json
// 200 OK
{ "status": "ok" }

// 403 Forbidden
{ "error": "forbidden" }

// 404 Not Found
{ "error": "not_member" }
```
