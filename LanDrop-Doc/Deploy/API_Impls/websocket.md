# LanDrop API 接入指南 — WebSocket 接入

> **版本**: v2.0  
> **最后更新**: 2026-05-23  
> **基础 URL**: `http://<host>:<port>`（默认 `http://0.0.0.0:8080`）

> 本文档从 `Deploy/api-impl.md` 拆分而来。完整 API 索引见 [README.md](README.md)。

---

## WebSocket 接入

### 8.1 连接

```
ws://<host>:<port>/ws?token=<access_token>
```

**认证：** 通过 Query Parameter `token` 传递 JWT access_token。无有效 token 则连接被拒绝（Close Code: VIOLATED_POLICY）。

**连接成功后：**
- 服务端发送 `CONNECTED` 事件到 core，注册用户在线状态
- 服务端通过 WebSocket 下行推送消息（聊天、房间事件、文件通知等）
- 服务端每 **20 秒**发送 ping 帧，客户端应回复 pong（见 8.4 心跳协议）

### 8.2 入站帧格式（客户端 → 服务端）

客户端发送 JSON 文本帧，通过 `type` 字段区分消息类型：

```json
// 聊天消息（群聊，含 elements）
{
  "type": "chat_message",
  "room_id": "PUBLIC",
  "content": "Hello!",
  "elements": [{"type":"text","content":"Hello!"}],
  "delivery_required": false
}

// 聊天消息（私聊）
{
  "type": "chat_message",
  "to": "u_bob12345",
  "content": "Hi Bob",
  "elements": [{"type":"text","content":"Hi Bob"}],
  "delivery_required": false
}

// 创建房间
{
  "type": "room_create",
  "name": "My Room",
  "password": "optional_password"
}

> **字段说明：** 房间名使用 `name` 或 `room_name` 均可，`name` 优先。

// 加入房间（已过时）
{
  "type": "room_join",
  "room_id": "A1B2C3",
  "password": "optional_password"
}

// 离开房间
{
  "type": "room_leave",
  "room_id": "A1B2C3"
}

// 撤回消息
{
  "type": "chat_recall",
  "message_id": "msg_abc123",
  "room_id": "PUBLIC"
}

// 编辑消息
{
  "type": "chat_edit",
  "message_id": "msg_abc123",
  "elements": [{"type":"text","content":"新内容"}],
  "room_id": "PUBLIC"
}

// 文件请求（上传/下载）
{
  "type": "file_request",
  "...": "..."
}

// ─── 房间管理（v1.4 新增）───

// 解散房间
{
  "type": "room_dissolve",
  "room_id": "A1B2C3"
}

// 踢出成员
{
  "type": "room_kick",
  "room_id": "A1B2C3",
  "target_user_id": "B2c3D4e5F6g7"
}

// 禁言成员
{
  "type": "room_mute",
  "room_id": "A1B2C3",
  "target_user_id": "B2c3D4e5F6g7"
}

// 解除禁言
{
  "type": "room_unmute",
  "room_id": "A1B2C3",
  "target_user_id": "B2c3D4e5F6g7"
}

// 发布公告
{
  "type": "room_announce",
  "room_id": "A1B2C3",
  "content": "公告内容"
}

// 邀请用户
{
  "type": "room_invite",
  "room_id": "A1B2C3",
  "invitee_ids": ["B2c3D4e5F6g7", "C3d4E5f6G7h8"]
}

// 回复邀请（同意/拒绝）
{
  "type": "room_invite_reply",
  "room_id": "A1B2C3",
  "invite_id": 1,
  "approve": true
}
```

**chat_message 字段说明：**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| type | string | 是 | 固定 `"chat_message"` |
| content | string | 是 | 纯文本内容（降级字段） |
| elements | array | 否 | 消息元素数组，结构见下表 |
| room_id | string | 群聊必填 | 目标房间 ID |
| to | string | 私聊必填 | 目标用户 ID |
| delivery_required | bool | 否 | 是否需送达确认（默认 false） |

**`elements` 元素类型：**

| type | 必填字段 | 示例 |
|------|---------|------|
| text | content | `{"type":"text","content":"Hello"}` |
| image | file_id, file_name, file_size | `{"type":"image","file_id":"f_123","file_name":"a.png","file_size":1024}` |
| picture | file_id | `{"type":"picture","file_id":"<file_id>"}` |
| file | file_id, file_name, file_size | `{"type":"file","file_id":"f_456","file_name":"a.pdf","file_size":2048}` |
| reply | message_id, preview | `{"type":"reply","message_id":"msg_orig","preview":{"from":"alice","text":"..."}}` |

**`picture` / `image` / `file` 类型说明（v2.0）：** 聊天媒体消息。客户端先通过 `PUT /api/rooms/{roomId}/images` 或 `POST /api/files/upload` 上传文件获取 `file_id`，再在消息 `elements` 中携带 `{"type":"picture","file_id":"<file_id>"}`。服务端自动写入 `chat_messages.storage_path` 和 `chat_messages.file_ref_id`（v2.0 新增）。下载时优先使用 `file_ref_id` 直接定位 `room_files` 表。撤回时自动标记对应文件过期。秒传：若已有相同 SHA-256，直接在目标房间新增一行（共享磁盘文件）。

消息可包含多个元素，如文本 + 图片混合发送。

**房间管理入站帧字段说明（v1.5）：**

| type | 必填字段 | 说明 |
|------|---------|------|
| room_dissolve | room_id | 解散房间 |
| room_kick | room_id, target_user_id | 踢出成员 |
| room_mute | room_id, target_user_id | 禁言成员 |
| room_unmute | room_id, target_user_id | 解除禁言 |
| room_announce | room_id, content | 发布公告 |
| room_invite | room_id, invitee_ids | 邀请用户（批量） |
| room_invite_reply | room_id, invite_id, approve | 同意/拒绝邀请 |
| room_promote | room_id, target_user_id | 晋升为管理员 (v1.5) |
| room_demote | room_id, target_user_id | 降级为普通成员 (v1.5) |
| event_confirm | event_id | 确认事件 (v1.5) |
| event_reject | event_id | 拒绝事件 (v1.5) |
| event_ack | event_id | 通用事件确认 (v1.6，兼容 join_request_ack/invite_ack) |
| chat_delete | message_id, room_id | 永久删除消息 (v1.5) |

**鉴权要求：** 所有房间管理操作均需操作者为房间 Admin 级以上（含），通过 WebSocket 连接时自动鉴权（基于连接绑定的 user_id）。

### 8.3 出站帧格式（服务端 → 客户端）

```json
// 聊天消息推送
{
  "type": "chat_message",
  "message_id": "uuid-xxx",
  "from": "A1b2C3d4E5f6",
  "display_name": "alice",
  "elements": [{"type":"text","content":"Hello!"}],
  "content": "Hello!",
  "timestamp": 1716000000000
}

// 带文件引用的消息
{
  "type": "chat_message",
  "message_id": "uuid-xxx",
  "from": "A1b2C3d4E5f6",
  "display_name": "alice",
  "room_id": "PUBLIC",
  "elements": [
    {"type":"text","content":"看看这张图"},
    {"type":"image","file_id":"f_abc12345","file_name":"photo.png","file_size":"1024000","mime_type":"image/png"}
  ],
  "content": "看看这张图",
  "file": {
    "file_id": "f_abc12345",
    "file_name": "photo.png",
    "file_size": "1024000",
    "mime_type": "image/png",
    "download_url": "/api/getfiles/by-id/f_abc12345"
  },
  "timestamp": 1716000000000
}

// 撤回通知（服务端广播给房间内其他成员）
{
  "type": "chat_recall",
  "message_id": "msg_abc123",
  "room_id": "PUBLIC"
}

// 编辑通知（服务端广播给房间内其他成员）
{
  "type": "chat_edit",
  "message_id": "msg_abc123",
  "elements": "[{"type":"text","content":"新内容"}]",
  "room_id": "PUBLIC"
}

// 加入申请通知（服务端 → 房间管理员）
{
  "type": "join_request",
  "event_id": "uuid-xxx",
  "room_id": "A1B2C3",
  "applicant_id": "user_001"
}

// 邀请通知（服务端 → 受邀人）
{
  "type": "invite_notify",
  "event_id": "uuid-xxx",
  "room_id": "A1B2C3",
  "inviter_id": "user_002"
}

// 申请已处理通知（服务端 → 其他管理员）
{
  "type": "join_request_handled",
  "room_id": "A1B2C3",
  "action": "approved",
  "by": "admin_id"
}

// 心跳 Ping
{
  "type": "ping",
  "timestamp": 1716000000000
}
```

**出站 chat_message 字段说明：**

| 字段 | 类型 | 说明 |
|------|------|------|
| from | string | 发送者 userId |
| display_name | string | 发送者在当前房间的头衔/昵称（由 room_members.display_name 或 users.display_name 回退） |
| room_id | string | 所属房间（群聊时存在） |
| to | string | 目标用户（私聊时存在） |
| file.download_url | string | 文件下载地址（`/api/getfiles/{roomId}/{fileId}`），需 JWT + 房间权限 |

**文件下载（v2.0）：** 带文件引用的消息中 `file.download_url` 字段提供直接下载链接。
- `GET /api/getfiles/{roomId}/{id}` — 优先 `file_ref_id` 直接定位 `room_files`，回退 `storage_path`
- 详见 [files.md](../files.md) §7.3

**说明：** 出站帧中绝不包含服务端内部路径（如 `storage_path`），确保信息安全。

### 8.4 心跳协议

服务端每 **20 秒**（`heartbeatIntervalMs`）发送 ping 帧。客户端收到后应回复 pong。

**入站格式（客户端 → 服务端）：**
```json
// 客户端回复 pong
{ "type": "pong" }
```

**出站格式（服务端 → 客户端）：**
```json
// 服务端发送 ping
{ "type": "ping", "timestamp": 1716000000000 }
```

**检测规则：**

| 参数 | 默认值 | 说明 |
|------|--------|------|
| heartbeatIntervalMs | 20000 | ping 发送间隔（毫秒） |
| maxLostPingCount | 3 | 连续未收到 pong 的次数上限 |
| readTimeoutMs | 90000 | 无任何帧到达的超时（毫秒） |

- 连续 `maxLostPingCount` 次 ping 未收到 pong → 服务端主动断连（Close Code: `GOING_AWAY`）
- `readTimeoutMs` 内未收到任何帧 → 服务端主动断连
- 客户端收到任何帧即重置超时计时器

**客户端注意事项：**
- 客户端收到 ping 帧时应尽快回复 pong
- 客户端可主动发送 ping，服务端立即回复 pong（不做计数）

---

### 8.5 Ack 送达确认

服务端发出的每条 `chat_message`、`room_*` 事件、`file_instruction` 均携带 `message_id`。客户端收到后应回复 Ack 以确认送达。

**入站格式（客户端 → 服务端）：**
```json
// 确认消息送达
{ "type": "ack", "message_id": "<收到帧的message_id>", "status": "ok" }

// 确认出错
{ "type": "ack", "message_id": "<收到帧的message_id>", "status": "error" }
```

**出站格式（服务端 → 客户端）：**
```json
// 服务端将 Ack 路由回消息发送方
{ "type": "ack", "ref_message_id": "<原始消息的message_id>", "status": "ok" }
```

**说明：**
- 客户端发送 `chat_message` 后，服务端**立即回复 Ack** 确认收到（`message_id` 为服务端分配的 ID）
- 消息送达目标客户端后，目标客户端应回复 Ack，服务端将其路由回原始发送者
- 服务端通过 `message_id` 追踪消息发送方，将 Ack 路由回原始发送者
- 若消息发送方已断连，Ack 丢弃并记录日志
- 不回复 Ack 不影响连接（心跳机制独立运作），但 `delivery_required=true` 的消息会重试推送

---

### 8.6 断连处理

服务端检测到 WebSocket 断开（主动关闭 / 心跳丢失 / 读取超时）后：
1. 发送 `DISCONNECTED` 事件到 core
2. core 标记用户离线，从所有房间移除
3. 通知相关房间成员

客户端断连重连后需重新建立 WebSocket 连接。若 access_token 过期，先通过 `/api/auth/refresh` 换新 token。
