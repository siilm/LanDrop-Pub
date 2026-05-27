# LanDrop API 接入指南 — 管理员 API

> **版本**: v1.5  
> **最后更新**: 2026-05-21  
> **基础 URL**: `http://<host>:<port>`（默认 `http://0.0.0.0:8080`）

> 本文档从 `Deploy/api-impl.md` 拆分而来。完整 API 索引见 [README.md](README.md)。

---

## 管理员 API

### 5.1 管理员列表

```
GET /api/public_admins
Authorization: Bearer <access_token>
```

**响应：**

```json
// 200 OK
[
  { "user_id": "u_abc12345", "username": "owner_85bf94cb" },
  { "user_id": "u_def67890", "username": "admin_alice" }
]
```

---

### 5.2 任命管理员

仅 Owner 可操作。

```
POST /api/public_admins
Authorization: Bearer <access_token>
Content-Type: application/json
```

**请求体：**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| user_id | string | 是 | 目标用户 ID |

**响应：**

```json
// 200 OK
{ "status": "appointed", "user_id": "u_def67890" }

// 403 Forbidden
{ "error": "owner_only" }

// 404 Not Found
{ "error": "user_not_found" }
```

---

### 5.3 解除管理员

仅 Owner 可操作。

```
DELETE /api/public_admins/{userId}
Authorization: Bearer <access_token>
```

**响应：**

```json
// 200 OK
{ "status": "removed" }

// 403 Forbidden
{ "error": "owner_only" }

// 404 Not Found
{ "error": "user_not_found" }
```

---

### 6.1 停用用户 (v1.5)

停用后用户无法登录，会话被吊销，但数据保留。

```
PUT /api/admin/users/{userId}/deactivate
Authorization: Bearer <access_token>
```

**权限要求：** Owner 或 PublicAdmin

**响应：**

```json
// 200 OK
{ "status": "deactivated" }

// 403 Forbidden
{ "error": "owner_only" }

// 404 Not Found
{ "error": "user_not_found" }
```

---

### 6.2 恢复用户 (v1.5)

```
PUT /api/admin/users/{userId}/activate
Authorization: Bearer <access_token>
```

**权限要求：** Owner 或 PublicAdmin

**响应：**

```json
// 200 OK
{ "status": "activated" }

// 403 Forbidden
{ "error": "owner_only" }

// 404 Not Found
{ "error": "user_not_found" }
```

---

### 6.3 封禁用户 (v1.5)

封禁用户将执行**级联清理**：
- 删除该用户创建的所有房间
- 删除该用户的全局角色（PublicAdmin 等）
- 移除该用户在所有房间的成员资格
- 删除该用户的邀请、加入申请、会话、信任设备
- 最后删除用户记录

```
DELETE /api/admin/users/{userId}
Authorization: Bearer <access_token>
```

**权限要求：** Owner 或 PublicAdmin

**响应：**

```json
// 200 OK
{ "status": "banned" }

// 403 Forbidden
{ "error": "owner_only" }

// 404 Not Found
{ "error": "user_not_found" }
```
