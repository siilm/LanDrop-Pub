# LanDrop API 接入指南 — 错误处理

> **版本**: v1.1  
> **最后更新**: 2026-05-19  
> **基础 URL**: `http://<host>:<port>`（默认 `http://0.0.0.0:8080`）

> 本文档从 `Deploy/api-impl.md` 拆分而来。完整 API 索引见 [README.md](README.md)。

---

## 错误处理

### 9.1 HTTP 状态码

| 状态码 | 说明 |
|--------|------|
| 200 | 成功 |
| 201 | 创建成功 |
| 202 | 已接受（如待审批） |
| 400 | 请求参数错误 |
| 401 | 未认证（token 缺失/无效/过期） |
| 403 | 权限不足 |
| 404 | 资源不存在 |
| 409 | 冲突（如重复注册） |

### 9.2 认证失败响应

当请求缺少有效 JWT 时，gateway 抛出 `AuthenticationException`，Ktor 默认返回 500。实际部署时建议配置 Ktor StatusPages 拦截，统一返回 401。

### 9.3 Token 过期处理流程

```
客户端请求 API (access_token 过期)
  → 收到 401
  → 自动调用 POST /api/auth/refresh { refresh_token }
  → 获得新 access_token
  → 重放原请求
```

若 refresh_token 也过期（14 天无活跃），需重新走完整登录流程。
