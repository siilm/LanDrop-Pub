# LanDrop API 接入指南

> **版本**: v1.5  
> **最后更新**: 2026-05-21  
> **基础 URL**: `http://<host>:<port>`（默认 `http://0.0.0.0:8080`）

本目录包含 LanDrop 的完整 API 文档，按功能模块拆分为以下文件：

---

## 文档索引

| 文件 | 内容 | 说明 |
|------|------|------|
| [auth.md](auth.md) | 认证概述 + 认证 API | Ed25519 挑战签名、JWT 登录/注册/刷新/登出/修改昵称/头像 |
| [system.md](system.md) | 系统 API | 健康检查、服务器状态 |
| "rooms/"(is a path) | 房间 API | 查阅./rooms/下的index了解所有的房间API |
| [admin.md](admin.md) | 管理员 API | 管理员任命/解除、列表查询 |
| [config.md](config.md) | 配置 API | 服务端配置读取与修改 |
| [files.md](files.md) | 文件 API | 文件上传、头像上传、聊天图片上传、静态文件下载/鉴权 |
| [websocket.md](websocket.md) | WebSocket 接入 | 连接、入站/出站帧格式、心跳、Ack、断连、房间管理 |
| [errors.md](errors.md) | 错误处理 | HTTP 状态码、认证失败、Token 过期处理 |
| [quickstart.md](quickstart.md) | 快速测试 | curl 示例、Python 客户端参考 |

---

## 认证方式速览

| 方式 | 适用场景 |
|------|---------|
| 无认证 | `/api/health`、`/api/auth/*` 登录/注册流程 |
| `Authorization: Bearer <jwt>` | 所有业务 API（HTTP Header） |
| `?token=<jwt>` | WebSocket 连接（Query Parameter） |

---

## 快速开始

1. 先阅读 [auth.md](auth.md) 了解 Ed25519 挑战签名 + JWT 认证流程
2. 查看具体业务 API：[rooms.md](rooms.md)、[files.md](files.md) 等
3. 实时通信参考 [websocket.md](websocket.md)
4. 直接用 [quickstart.md](quickstart.md) 中的 curl 命令快速测试

---
