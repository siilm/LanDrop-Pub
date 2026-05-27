# LanDrop

基于 Kotlin/Ktor 的局域网文件共享与即时通讯服务端。

---

## 功能概览

| 模块 | 说明 |
|------|------|
| 用户系统 | Ed25519 挑战签名登录、JWT 认证、用户注册 |
| 房间 | 创建/解散/加入/邀请、角色管理（Owner/Admin/Member）、禁言 |
| 消息 | 文本/图片/文件消息、撤回、WebSocket 实时推送 |
| 文件 | 上传（秒传检测）、下载（断点续传）、头像、聊天图片 |
| 事件系统 | 加入审批、邀请审批，事件确认/Ack 机制 |
| 管理 | CLI 用户管理、全局角色（Owner/Public Admin）、服务端配置 |

---

## 技术栈

- **语言**: Kotlin 2.1
- **框架**: Ktor 3.0（Netty 引擎）
- **数据库**: MySQL / H2 / SQLite（Exposed ORM）
- **认证**: Ed25519 签名 + JWT（jjwt）
- **序列化**: kotlinx-serialization + Protobuf
- **构建**: Gradle 9.x + Shadow JAR
- **AI Attention**: 为了使项目快速实现，使用了AI编程，可能留有一些死代码，但不影响功能

---

## 项目结构

```
landrop/
├── core/          # 核心业务逻辑（房间、消息、文件、认证、权限）
├── gateway/       # HTTP/WebSocket 网关入口（Ktor）
├── proto/         # Protobuf 定义（内部通信）
├── shared/        # 共享常量
├── LanDrop-Doc/   # 项目文档（API 文档）
└── gradle/        # Gradle Wrapper
```

---

## 部署流程

### 1. 环境要求

- JDK 21+
- MySQL 8.0+（或使用 H2/SQLite 作为嵌入式数据库，尚未完全支持）
- 磁盘空间（文件存储）
- 图床/OSS云存储（正在适配）

### 2. 构建

```bash
# 克隆项目
git clone <repo> && cd landrop

# 构建 fat JAR
./gradlew :gateway:shadowJar

```

产物位于 `gateway/build/libs/gateway-all.jar`。

### 3. 配置文件

首次运行后，将产生配置文件到jar根目录下，然后程序会退出，请你按实际情况修改后再次开启。

**关键配置项**：

```properties
# 监听地址
server.host=0.0.0.0
server.port=8080

# 数据库（生产环境建议 MySQL）
db.url=jdbc:mysql://localhost:3306/landrop?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai
db.user=your_db_user
db.password=your_db_password
db.driver=com.mysql.cj.jdbc.Driver

# JWT 密钥（务必修改为强随机字符串，至少 32 字节）
jwt.secret=your-strong-secret-key-at-least-32-bytes-long

# 文件存储目录
landrop.file.base_dir=./landrop-files

# 用户密钥存储目录
landrop.secrets_dir=./landrop-files/secrets

# 每个成员最多可创建的房间数
landrop.config.max_rooms_per_member=2
```

> **提示**: 如果使用 H2 或 SQLite 作为轻量数据库，注释 MySQL 相关配置并启用 H2/SQLite 配置块即可。

### 4. 启动服务

```bash
java -jar gateway/build/libs/gateway-all.jar
```

服务启动后默认监听 `0.0.0.0:8080`。

### 5. 创建 Owner&新用户

首次启动后，系统自动创建 Owner 用户（CLI 直接操作数据库，无需网络），并会打印Owner的信息，后续不会再次打印。

若后续需要创建新用户，使用指令在服务器本地创建，或登录Owner（必须）使用API创建，创建后，不会传输新用户的私钥，Owner的密钥固定会在`./owner-secret`，其它用户的密钥则可在配置文件指定，默认在`./landrop-files/secrets/[user_id]`

也支持使用指令进行一些管理操作，使用`?/help`了解详细指令，若检测不到CLI环境，无法使用控制台指令（但API仍能实现）。

CLI 常用命令：

```bash
# 创建用户
useradd <username> [userid]

# 删除用户（级联删除其房间）
userdel <userid>

# 列出用户
userlist
```

### 6. 验证部署

```bash
# 健康检查
curl http://localhost:8080/api/health
# -> {"status":"ok"}
```

完整 API 接入指南见 `LanDrop-Doc/Deploy/API_Impls/README.md`。

---

## 注意事项

### 权限分级

- Owner/Public Admin不会被添加到新创建的房间，除非他们希望加入（将会强制加入），作为特权角色，创建房间不受配额限制

| 角色 | 范围 | 简要说明 |
|------|------|----------|
| **Owner** | 全局 | 最高权限，可管理任意房间、强制解散、任命/解职 Admin（任意房间）、任免 PublicAdmin |
| **PublicAdmin** | 全局（但仅限 Member 创建的房间） | 由 Owner 任命，可管理所有 **Member 创建的** 房间（强制进入、发布公告、踢人、删消息、禁言等），拥有 Admin 的全部权限 |
| **Creater** | 房间级 | Member 创建房间后自动成为该房间的 Creater，拥有该房间的 Owner 级别权限（任命/解职 Admin、解散房间），但受全局 Owner 制约 |
| **Admin** | 房间级 | 由 Owner 或 Creater 在具体房间内任命，可管理该房间（公告、踢人、删消息、禁言） |
| **Member** | 房间级（成员资格） | 基础用户：可创建房间（受配额限制）、受邀/申请加入房间、发送/撤回自己的消息、转发消息等 |

### 数据库

- **首次启动自动建表**：无需手动执行 SQL，Exposed 会根据实体定义自动创建表结构。
- MySQL 建议使用 InnoDB 引擎并设置字符集为 `utf8mb4`。
- **H2 内存模式**（`jdbc:h2:mem:landrop`）数据不持久化，重启即丢失，仅适合测试。

### JWT 密钥

- `jwt.secret` 必须修改为强随机字符串，否则 Token 可被轻易伪造。
- 生产环境建议使用 64 字节以上的随机字符串。

### 文件存储

- 所有上传文件存储在 `landrop.file.base_dir` 配置的目录下。
- 已解散房间的数据库记录会在 168 小时后自动清理，但磁盘文件不会被删除（需手动清理）。
- 临时文件过期时间由 `landrop.file.expiration_hours` 控制（默认 168 小时）。

### 安全

- 用户密钥（Ed25519 私钥）存储在 `landrop.secrets_dir` 目录，请确保该目录仅服务进程可读。
- 生产环境建议开启 HTTPS 反向代理（如 Nginx/Caddy），并在代理层处理 TLS。

### 网络

- 服务端默认监听 `0.0.0.0`，客户端通过局域网 IP 访问。
- WebSocket 连接使用 `/ws?token=<jwt>` 认证。

### 性能

- 默认房间最大成员数 500，可在 `landrop.properties` 中修改 `landrop.room.max_members`。
- 文件上传支持秒传检测（SHA-256 比对），重复文件不占用额外存储。
- 文件下载支持断点续传（HTTP Range 请求）。

---

## 更多文档

| 文档 | 路径 |
|------|------|
| API 接入指南 | `LanDrop-Doc/Deploy/API_Impls/README.md` |


---

## License

 ![Apache-2.0 license](./LICENSE)
