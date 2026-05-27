# LanDrop API 接入指南 — 配置 API

> **版本**: v1.1  
> **最后更新**: 2026-05-19  
> **基础 URL**: `http://<host>:<port>`（默认 `http://0.0.0.0:8080`）

> 本文档从 `Deploy/api-impl.md` 拆分而来。完整 API 索引见 [README.md](README.md)。

---

## 配置 API

### 6.1 获取配置

```
GET /api/config
Authorization: Bearer <access_token>
```

**响应：**

```json
// 200 OK
{
  "max_rooms_per_member": "2",
  "allow_member_create_rooms": "true",
  "...": "..."
}
```

---

### 6.2 修改配置

仅 Owner 可操作。

```
PATCH /api/config/{key}
Authorization: Bearer <access_token>
Content-Type: application/json
```

**请求体：**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| value | string | 是 | 配置新值 |

**响应：**

```json
// 200 OK
{ "status": "updated" }

// 403 Forbidden
{ "error": "owner_only" }
```
