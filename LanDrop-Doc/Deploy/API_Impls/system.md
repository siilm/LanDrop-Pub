# LanDrop API 接入指南 — 系统 API

> **版本**: v1.1  
> **最后更新**: 2026-05-19  
> **基础 URL**: `http://<host>:<port>`（默认 `http://0.0.0.0:8080`）

> 本文档从 `Deploy/api-impl.md` 拆分而来。完整 API 索引见 [README.md](README.md)。

---

## 系统 API

### 3.1 健康检查

无需认证。用于负载均衡器或监控探针。

```
GET /api/health
```

**响应：**

```json
// 200 OK
{"status":"ok"}
```

---

### 3.2 服务器状态

需要 JWT 认证。

```
GET /api/status
Authorization: Bearer <access_token>
```

**响应：**

```json
// 200 OK
{
  "status": "ok",
  "online": "3"
}
```

| 字段 | 说明 |
|------|------|
| online | 当前在线 WebSocket 连接数 |
