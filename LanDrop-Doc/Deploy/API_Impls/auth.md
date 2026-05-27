# LanDrop API 接入指南 — 认证

> **版本**: v1.5  
> **最后更新**: 2026-05-24  
> **基础 URL**: `http://<host>:<port>`（默认 `http://0.0.0.0:8080`，可通过 `landrop.properties` 中 `server.host`/`server.port` 配置）

> 本文档从 `Deploy/api-impl.md` 拆分而来。完整 API 索引见 [README.md](README.md)。

---

## 1. 认证概述

LanDrop 采用 **Ed25519 非对称密钥 + 挑战签名 + JWT** 认证体系。

### 认证方式

| 方式 | 适用场景 |
|------|---------|
| 无认证 | `/api/health`、`/api/auth/login`、`/api/auth/verify`、`/api/auth/refresh` 登录流程 |
| `Authorization: Bearer <jwt>` | 所有业务 API（HTTP Header） |
| `?token=<jwt>` | WebSocket 连接（Query Parameter） |

### Token 生命周期

| Token | 有效期 | 用途 |
|-------|--------|------|
| access_token (JWT) | 15 分钟 | 所有 API 请求鉴权 |
| refresh_token (不透明) | 14 天 | 换发新 access_token |

### 认证流程概要

```
客户端                                    服务端
  │                                         │
  │ 1. POST /api/auth/login                 │
  │    {"user_id"}             │
  │ ──────────────────────────────────────► │ 生成随机 challenge
  │ ◄── {"temp_session_id","challenge"}     │ + temp_session_id (60s有效)
  │                                         │
  │ 2. 私钥签名:                             │
  │    data = challenge || device_info       │
  │          || user_id                      │
  │    sig = Ed25519.sign(sk, data)          │
  │                                         │
  │ 3. POST /api/auth/verify                │
  │    {"temp_session_id","signature"}       │
  │ ──────────────────────────────────────► │ Ed25519 验签
  │ ◄── {"access_token","refresh_token"}    │ 签发 JWT + refresh token
  │                                         │
  │ 4. 后续请求:                              │
  │    Authorization: Bearer <access_token>  │
  │ ═══════════════════════════════════════► │ 本地验 JWT，不调 core
```

---

## 2. 认证 API（无需 JWT）

### 2.1 用户注册

注册新用户。Ed25519 密钥对由系统自动生成，私钥写入服务器文件系统（路径可配置），HTTP 响应中不包含私钥。userId 可自定义（12位字母数字），留空则自动生成。

> **鉴权要求**: Owner 或 PublicAdmin 可调用此接口，需携带 `Authorization: Bearer <access_token>`（JWT 中 `global_role` 为 `owner` 或 `public_admin`）。

```
POST /api/auth/register
Authorization: Bearer <access_token>
Content-Type: application/json
```

**请求体：**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| username | string | 是 | 昵称（最长 25 字符） |
| user_id | string | 否 | 自定义用户 ID（12位字母数字，格式 `^[A-Za-z0-9]{12}$`），留空自动生成 |

**响应：**

```json
// 201 Created
{
  "user_id": "A1b2C3d4E5f6",
  "username": "alice",
  "global_role": "member"
}

// 400 Bad Request
{ "error": "username is required" }

// 400 Bad Request
{ "error": "username max 25 characters" }

// 400 Bad Request
{ "error": "username_too_long" }

// 400 Bad Request
{ "error": "invalid_user_id_format" }

// 400 Bad Request
{ "error": "user_id must be 12 alphanumeric characters" }

// 409 Conflict
{ "error": "user_id_collision_retry" }

// 403 Forbidden
{ "error": "owner_or_public_admin_required" }
```

**密钥文件输出：**

系统自动生成的 Ed25519 密钥对写入服务器文件系统，默认路径：

```
{landrop.secrets_dir}/{user_id}/
├── {user_id}.key    # 私钥（Base64）
└── {user_id}.pub    # 公钥（Base64）
```

- `landrop.secrets_dir` 默认为 `./landrop-files/secrets`，可在 `landrop.properties` 中配置
- HTTP 响应中**不包含**私钥，管理员需通过服务器文件系统获取密钥并安全分发给用户

**说明：**
- 新用户全局角色固定为 `member`
- 不会写入 `global_roles` 表（该表仅存放 `owner`、`public_admin`）
- 注册后自动加入 PUBLIC 房间


---

### 2.2 登录第一步：请求挑战

```
POST /api/auth/login
Content-Type: application/json
```

**请求体：**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| user_id | string | 是 | 用户 ID（登录凭证） |

**响应：**

```json
// 200 OK
{
  "temp_session_id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "challenge": "base64_encoded_32_random_bytes..."
}

// 404 Not Found
{ "error": "user_not_found" }

// 403 Forbidden
{ "error": "user_inactive" }
```

**说明：**
- `temp_session_id` 有效期 60 秒
- `challenge` 为 Base64 编码的 32 字节随机值
- 客户端需在第二步签名时使用 device_info 参与签名构造

---

### 2.3 登录第二步：签名验证

```
POST /api/auth/verify
Content-Type: application/json
```

**请求体：**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| temp_session_id | string | 是 | 步骤 2.2 返回的临时会话 ID |
| signature | string | 是 | Ed25519 签名，Base64 编码 |
| device_info | string | 否 | 设备信息（需与步骤 2.2 一致） |

**签名原文构造：**
```
data = challenge || compact_json(device_info) || user_id
signature = Ed25519.sign(private_key, data)
```

**响应：**

```json
// 200 OK
{
  "access_token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "refresh_token": "rt_a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6",
  "expires_in": "900",
  "refresh_expires_in": "1209600",
  "user_id": "A1b2C3d4E5f6",
  "username": "alice",
  "global_role": "member"
}

// 401 Unauthorized
{ "error": "invalid_signature" }

// 401 Unauthorized
{ "error": "challenge_expired" }

// 401 Unauthorized
{ "error": "auth_failed" }
```

---

### 2.4 刷新 access_token

当 access_token 过期时，使用 refresh_token 换发新 token（无需重新签名）。

```
POST /api/auth/refresh
Content-Type: application/json
```

**请求体：**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| refresh_token | string | 是 | 步骤 2.3 获得的 refresh_token |

**响应：**

```json
// 200 OK
{
  "access_token": "eyJhbGciOi... (新 JWT)",
  "expires_in": "900"
}

// 401 Unauthorized
{ "error": "refresh_token_expired" }

// 401 Unauthorized
{ "error": "token_revoked" }

// 401 Unauthorized
{ "error": "invalid_token" }
```

---

### 2.5 登出

撤销当前会话的所有 token。

```
POST /api/auth/logout
Authorization: Bearer <access_token>
Content-Type: application/json
```

**请求体：**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| session_id | string | 否 | 指定会话 ID；不传则撤销当前会话 |

**响应：**

```json
// 200 OK
{ "status": "logged_out" }
```

---

### 2.6 修改用户名

修改当前用户的 username（昵称），最大 25 字符。不强制 session 失效。

```
PUT /api/auth/rename_username
Authorization: Bearer <access_token>
Content-Type: application/json
```

**请求体：**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| new_username | string | 是 | 新昵称（最大 25 字符） |

**响应：**

```json
// 200 OK
{ "username": "NewName" }

// 400 Bad Request
{ "error": "username_too_long", "message": "username max 25 chars" }

// 404 Not Found
{ "error": "user_not_found" }
```

---

### 2.7 头像上传

上传用户头像。上传后自动写入 `users.avatar_url` 字段。图片格式要求 1:1 比例，最大 1024×1024。

```
PUT /api/auth/avatar
Authorization: Bearer <access_token>
Content-Type: multipart/form-data
```

> **存储模式**: 由 `landrop.storage.mode` 配置决定（`local` / `cloud`）。
> - **local 模式**: 保存到 `landrop-files/{userId}/avatar/{userId}.jpg`，avatar_url 写入本地路径（通过 `/api/getfiles/avatar/` 端点暴露）
> - **cloud 模式**: 保存到 `cloud-cache/avatar/` 下并打印日志，等待专用上传工具接管（TODO）

**响应：**

```json
// 200 OK
{ "success": true, "avatar_url": "/api/getfiles/avatar/A1b2C3d4E5f6" }

// 400 Bad Request
{ "error": "no_file", "message": "No file in request" }

// 404 Not Found
{ "error": "user_not_found" }
```

**头像获取：**
- 客户端通过 `GET /api/getfiles/avatar/{user_id}` 下载头像（需 JWT，自动匹配扩展名）
- 若 `avatar_url` 为 HTTP 链接（图床），直接使用；若为本地路径，自动转换为 API 地址
- 房间成员列表中的 `avatar_url` 字段已自动完成转换

---

### 2.8 查询当前用户角色 (whoami)

返回当前 JWT 对应用户的全局角色（从数据库实时查询，非 JWT 中缓存的角色）。

```
GET /api/auth/whoami
Authorization: Bearer <access_token>
```

**响应：**

```json
// 200 OK
{
  "user_id": "A1b2C3d4E5f6",
  "role": "member"
}
```

| role 值 | 说明 |
|---------|------|
| `owner` | 系统 Owner |
| `public_admin` | 公共管理员 |
| `member` | 普通成员 |

**说明：**
- 角色值对应 `users.global_role` 字段，与 `global_roles` 表保持一致
- creater/admin 为房间级角色（仅在 `room_members.role` 中），不在此接口返回
