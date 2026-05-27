# LanDrop API 接入指南 — 快速测试

> **版本**: v1.1  
> **最后更新**: 2026-05-19  
> **基础 URL**: `http://<host>:<port>`（默认 `http://0.0.0.0:8080`）

> 本文档从 `Deploy/api-impl.md` 拆分而来。完整 API 索引见 [README.md](README.md)。

---

## 附录：快速测试

```bash
# 1. 健康检查
curl http://localhost:8080/api/health
# → {"status":"ok"}

# 2. 请求挑战
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"user_id":"owner_85bf94cb","device_info":"{\"os\":\"linux\"}"}'
# → {"temp_session_id":"...","challenge":"..."}

# 3. Ed25519 签名后验证（需客户端完成签名）
curl -X POST http://localhost:8080/api/auth/verify \
  -H "Content-Type: application/json" \
  -d '{"temp_session_id":"...","signature":"base64_sig"}'
# → {"access_token":"eyJ...","refresh_token":"rt_..."}

# 4. 带 JWT 访问业务 API
export TOKEN="eyJ..."
curl http://localhost:8080/api/rooms -H "Authorization: Bearer $TOKEN"
curl http://localhost:8080/api/status -H "Authorization: Bearer $TOKEN"

# 5. WebSocket 连接（需 wscat 或浏览器）
# wscat -c "ws://localhost:8080/ws?token=$TOKEN"
```

### Python 客户端示例

参见 `scripts/demo_owner_chat.py`，提供完整的 Ed25519 签名 + JWT 登录 + WebSocket 聊天流程。
