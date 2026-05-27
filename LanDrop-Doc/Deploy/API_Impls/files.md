# LanDrop API 接入指南 — 文件 API

> **版本**: v2.0  
> **最后更新**: 2026-05-23  
> **基础 URL**: `http://<host>:<port>`（默认 `http://0.0.0.0:8080`）

---

## 数据表职责（v2.0）

| 表 | 用途 | room_id | checksum |
|----|------|:---:|:---:|
| `room_files` | 聊天媒体最终存储 | `VARCHAR(16)` 单房间 | ✅ (秒传匹配) |
| `files` | 上传中转临时表（48h 清理） | `VARCHAR(16)` | ✅ (`file_hash`) |
| `chat_messages` | 聊天消息 — `file_ref_id` 关联 `room_files` | — | — |

- **`room_files`**: 每个文件在每个房间独立一行。同一文件（相同 checksum）可在多个房间各有一行，共享磁盘文件。
- **`files`**: 上传请求暂存，完成后移入 `room_files`，48h 后 CleanupJobs 清理。
- **`chat_messages.file_ref_id`**: 多媒体消息直接填写 `room_files.file_id`，下载优先走此字段。

---

## 文件 API

### 7.1 文件上传

```
POST /api/files/upload
Authorization: Bearer <access_token>
Content-Type: multipart/form-data
```

| 表单字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| file | binary | 是 | 文件内容 |
| room_id | string | 是 | 归属房间 ID |

**响应:**

```json
{ "file_id": "067f7c21-...", "file_name": "067f7c21-..._doc.pdf", "file_size": "1024000", "status": "uploaded" }
```

**流程:** gateway 计算 SHA-256 → core 写入 `files` 中转表 → 上传完成清除中转记录 → 客户端 WebSocket 消息引用 `file_id`

---

### 7.2 文件秒传（v2.0）

#### 7.2.1 秒传检测

```
POST /api/files/check
Authorization: Bearer <access_token>
Body: { "sha256": "...", "room_id": "..." }
```

- 在 `room_files` 中按 checksum 查找
- 找到 → 目标房间新增一行（共享磁盘文件）→ `{ "status": "instant", "file_id": "..." }`
- 未找到 → `{ "status": "upload_required" }`

#### 7.2.2 头尾验证（≥10MB 大文件）

```
POST /api/files/check/verify
Body: { "file_id": "...", "head_sha256": "...", "tail_sha256": "...", "room_id": "..." }
```

服务端读本地文件计算头/尾 1MB SHA-256 比对，通过后秒传。

---

### 7.3 文件下载

#### 7.3.1 通用下载

```
GET /api/files/{fileId}
```

按 `fileId` 查 `room_files` 返回文件。

#### 7.3.2 房间文件下载（v2.0 查找顺序）

```
GET /api/getfiles/{roomId}/{id}
```

1. **优先 `file_ref_id`** — `id` 作为 `message_id` 查 `chat_messages`，取 `file_ref_id` 直接定位 `room_files`
2. **回退 `storage_path`** — 兼容旧消息
3. **补查 `file_id`** — `id` 直接作为 `file_id` 查 `room_files`

---

### 7.4 聊天图片上传

```
PUT /api/rooms/{roomId}/images
Authorization: Bearer <access_token>
Content-Type: multipart/form-data
```

上传后自动写入 `room_files` 并计算 SHA-256。已有相同 checksum → 秒传新增行。

```json
// 200
{ "success": true, "avatar_url": "<file_id>" }
```

---

### 7.5 房间文件删除

```
DELETE /api/rooms/{roomId}/files/{fileId}
```

按 `file_id + room_id` 精确定位，标记 `expires_at`。CleanupJobs 后续清理。

---

### 7.6 文件生命周期

| 操作 | `expires_at` | 磁盘 |
|------|:---:|:---:|
| 上传 | NULL | 保留 |
| 撤回 / 删除 | `now + 168h` | CleanupJobs 清理 |
| CleanupJobs (room_files) | — | 删除 |
| CleanupJobs (files) | — | 48h 删除中转记录 |

---

### 7.7 存储结构

**`room_files`（v2.0）：**

| 列 | 类型 | 说明 |
|----|------|------|
| room_id | VARCHAR(16) | 单房间 ID |
| file_id | VARCHAR(64) | UUID（普通索引） |
| file_name | VARCHAR(512) | 处理后文件名 `{fileId}_{safeName}` |
| file_size | BIGINT | 字节 |
| mime_type | VARCHAR(128) | — |
| storage_path | VARCHAR(1024) | 磁盘绝对路径 |
| checksum | VARCHAR(128) | SHA-256 |
| expires_at | BIGINT | NULL=永久 |

**`files`（v2.0 中转表）：**

| 列 | 类型 | 说明 |
|----|------|------|
| room_id | VARCHAR(16) | 目标房间 |
| file_id | VARCHAR(64) | UUID |
| file_hash | VARCHAR(128) | SHA-256 |
| user_id | VARCHAR(64) | 上传者 |
| started_at | BIGINT | 开始时间 |
| expires_at | BIGINT | 48h 过期 |
