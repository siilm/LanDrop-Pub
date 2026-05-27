# LanDrop API 接入指南 — 房间 · 媒体文件

> **版本**: v2.0  
> **最后更新**: 2026-05-23  
> **基础 URL**: `http://<host>:<port>`（默认 `http://0.0.0.0:8080`）

> 房间 API 完整索引见 [INDEX.md](INDEX.md)。

---

## 聊天图片上传

### 4.12 聊天图片上传

```
PUT /api/rooms/{roomId}/images
Authorization: Bearer <access_token>
Content-Type: multipart/form-data
```

**说明：**
- 上传后自动写入 `room_files` 表，计算并存储 SHA-256 校验值
- 已有相同 checksum 的文件 → 秒传，在目标房间新增一行（共享磁盘文件）
- 返回 `file_id`（UUID），客户端在 WebSocket `chat_message` 的 `elements` 中携带 `{"type":"picture","file_id":"<file_id>"}` 发送图片消息
- 下载通过 `GET /api/getfiles/{roomId}/{fileId}`

**存储路径：**
- local 模式: `landrop-files/{roomId}/chatimg/{uuid}.{ext}`

**响应：**

```json
// 200 OK
{ "success": true, "avatar_url": "<file_id>" }

// 400
{ "error": "no_file", "message": "No image in request" }
```

> `avatar_url` 字段名兼容旧版，实际值为 UUID 格式的 `file_id`。

---

## 房间文件管理

### 4.13 房间文件列表

```
GET /api/rooms/{roomId}/files
Authorization: Bearer <access_token>
```

列出指定房间的所有媒体文件（`room_files` 表）。

**响应：**

```json
{
  "room_id": "PUBLIC",
  "count": "2",
  "files": [
    {
      "file_id": "067f7c21-...",
      "file_name": "photo.jpg",
      "file_size": "2048000",
      "mime_type": "image/jpeg",
      "uploader_id": "",
      "uploaded_at": "1716000000000"
    }
  ]
}
```

> **v2.0**: `uploader_id` 字段不再记录（始终返回空字符串，保留字段以兼容客户端）。

---

### 4.14 删除房间文件

```
DELETE /api/rooms/{roomId}/files/{fileId}
Authorization: Bearer <access_token>
```

按 `file_id + room_id` 精确定位行，标记 `expires_at`（不立即删除磁盘文件）。CleanupJobs 定期统一清理。

**鉴权：** 房间管理员。

**响应：**

```json
// 200 OK
{ "status": "marked_for_cleanup", "expires_at": "1716508800000" }

// 403
{ "error": "forbidden" }

// 404
{ "error": "not_found" }
```

---

## 文件下载

### 4.15 文件/图片下载（v2.0 查找顺序）

```
GET /api/getfiles/{roomId}/{id}
Authorization: Bearer <access_token>
```

**查找顺序：**

1. **优先 `file_ref_id`** — `id` 作为 `message_id` 查 `chat_messages`，若有 `file_ref_id` 则直接定位 `room_files`
2. **回退 `storage_path`** — 兼容无 `file_ref_id` 的旧消息
3. **补查 `file_id`** — `id` 直接作为 `file_id` 查 `room_files`

**鉴权：** JWT + 房间成员/管理员权限。

| 状态码 | 说明 |
|--------|------|
| 200 | 文件二进制流 |
| 401 | 未认证 |
| 403 | 非房间成员 |
| 404 | 文件不存在 |

---

## 文件生命周期（v2.0）

| 操作 | `expires_at` | 磁盘文件 |
|------|:---:|:---:|
| 上传 | NULL（永久） | 保留 |
| 撤回文件消息 | `now + 168h` | 保留至 CleanupJobs 清理 |
| 显式删除 | `now + 168h` | 保留至 CleanupJobs 清理 |
| CleanupJobs 清理 | — | 删除 |

> `expiration_hours` 默认 **168 小时（7 天）**。

---

## 数据表参考（v2.0）

**`room_files` 表：**

| 列 | 类型 | 说明 |
|----|------|------|
| room_id | VARCHAR(16) | 单房间 ID |
| file_id | VARCHAR(64) | UUID（普通索引，同一文件可多行） |
| file_name | VARCHAR(512) | 处理后文件名 `{fileId}_{safeName}` |
| file_size | BIGINT | 字节 |
| mime_type | VARCHAR(128) | 默认 `application/octet-stream` |
| storage_path | VARCHAR(1024) | 磁盘绝对路径 |
| checksum | VARCHAR(128) | SHA-256（秒传匹配） |
| expires_at | BIGINT | NULL=永久 |
