# LanDrop API 接入指南 — 房间 API

> **版本**: v1.6  
> **最后更新**: 2026-05-24  
> **基础 URL**: `http://<host>:<port>`（默认 `http://0.0.0.0:8080`）

> 本文档从 `Deploy/api-impl.md` 拆分而来。返回顶层索引见 [../README.md](../README.md)。

---

## 子文档索引

| 文件 | 内容 | API 列表 |
|------|------|---------|
| [lifecycle.md](lifecycle.md) | 房间生命周期 | mine, all, create, join, force-join, dissolve, join-requests, approve/reject |
| [messages.md](messages.md) | 消息系统 | 消息列表, 编辑, 撤回(WS), 公告 |
| [members.md](members.md) | 成员管理 | 成员列表, 头衔(自己/他人), 踢出, 禁言 |
| [media.md](media.md) | 媒体文件 | 图片上传, 文件列表, 删除文件, 下载 |
| [invites.md](invites.md) | 邀请系统 | 邀请, 待审批列表, 同意, 拒绝 |

---

## 权限角色速览

### 全局角色 (users.global_role)

| 角色 | 权限 |
|------|------|
| `owner` | 全局全权限，可晋升 public_admin |
| `public_admin` | 管理所有 member 房间（视为 creater），由 owner 晋升 |
| `member` | 普通用户 |

### 房间角色 (room_members.role)

| role 值 | 名称 | 权限层级 | 说明 |
|---------|------|:--:|------|
| 2 | Creater | 3/2 | 房间创建者，仅在本房间拥有全权限 |
| 1 | Admin | 1 | 房间管理员，由 Creater 任命 |
| 0 | Member | 0 | 普通成员 |
| -1 | Muted | — | 禁言状态，不可发消息 |

> 权限层级: owner(3) > public_admin(2) = creater(2) > admin(1) > member(0)
> public_admin 在管理范围内视为 creater，可强制加入、解散、任命管理等

---

## 快速导航

- **想建房间/加入/解散？** → [lifecycle.md](lifecycle.md)
- **想发消息/编辑/撤回？** → [messages.md](messages.md)
- **想管成员/踢人/禁言？** → [members.md](members.md)
- **想上传图片/传文件？** → [media.md](media.md)
- **想邀请/审批？** → [invites.md](invites.md)
