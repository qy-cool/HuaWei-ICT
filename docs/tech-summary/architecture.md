# cloide-shell 系统架构

> 本文由迁移后的代码、Flyway 迁移、OpenAPI/协议文档整理；不引用旧需求规范。
> 契约以 `docs/openapi/` 为准，消息/WS 协议以 `docs/protocol/` 为准。

## 1. 定位与端

cloide-shell 是一个服务器监控与运维平台：Go Agent 在被监控主机上采集指标并经
WebSocket 上报；Java 后端负责认证、存储、告警、可靠消息、终端中继与 AI 诊断；
Android 客户端提供监控、告警、终端与 AI 功能。当前没有 Web 前端，`web/` 留空。

| 端 | 目录 | 技术栈 | 职责 |
|---|---|---|---|
| 后端 | `server/` | Java 21 / Spring Boot 3.4.13 / MyBatis-Plus 3.5.12 / Flyway | REST、WebSocket、告警、Outbox、终端中继、AI |
| Agent | `agent/` | Go 1.23 / gopsutil / creack-pty / coder-websocket | 指标采集、心跳、离线缓冲、PTY 终端、受限命令 |
| Android | `android/` | Kotlin 2.0.21 / Compose / Hilt / Retrofit / OkHttp | 登录、服务器、监控、告警、终端、AI |
| 部署 | `deploy/` | systemd / Nginx / Shell | 安装、升级、备份、恢复、健康检查 |
| 回归 | `scripts/` | Node `.mjs` / Playwright（旧前端遗留已删） | HTTP/WS 端到端、契约校验 |

## 2. 后端模块

包根 `cloide.server`，分层 `config / common / security / module / client / websocket / ssh / scheduler`。

| 模块 | 目录 | 职责 |
|---|---|---|
| 认证 | `module/auth` | 注册、登录、JWT、首管理员一次性初始化令牌、登出黑名单 |
| 管理员 | `module/admin` | 用户分页、单个/批量审核 |
| 服务器 | `module/server` | 服务器 CRUD、SSH 主机指纹、SSH 测试与历史、Agent Token、Monitor Ticket |
| 指标 | `module/metrics` | 指标落库/查询、幂等与乱序拒绝、最新值/历史/进程/资源快照 |
| 告警 | `module/alert` | 规则 CRUD、状态机、记录、通知、Outbox 消费、DLQ/重放 |
| 终端 | `module/terminal` | 终端会话生命周期、Monitor↔Agent 中继、流控 |
| AI | `module/ai` | 只读诊断、运维问答、告警解释、定时健康报告、Provider 配置 |
| 命令域 | `module/command` | 白名单模板、审批制/自动审批、审计、观察期评审 |
| 系统 | `module/system` | health/ready、RabbitMQ consumers/queues 快照 |

横切：`security`（JWT、AES-GCM、RBAC、限流）、`websocket`（Agent/Monitor 通道）、
`ssh`（sshj、主机指纹、CIDR 策略）、`scheduler`（指标/告警/通知/AI 清理任务）。

## 3. 运行时链路

### 3.1 REST

Android/API 客户端 → Nginx（TLS 终止）→ `127.0.0.1:18080`。统一响应信封
`{code, message, data}`；错误码见 `docs/openapi/README.md`；请求带
`X-Correlation-ID` 便于追踪。

### 3.2 Agent WebSocket

`/ws/agent`：Agent 首帧 `agent.authenticate`（Token），随后心跳、指标上报、
终端帧、命令帧。指标采用 ACK 确认；未确认指标留在本地 FIFO（默认
`/var/lib/cloide/metrics-buffer.json`），断线重连后按最小间隔重放。协议见
`docs/protocol/websocket-protocol.md`。

### 3.3 Monitor WebSocket

`/ws/monitor`：客户端先 `POST /api/ws/monitor-ticket` 取一次性 30s ticket，
再建链订阅服务器；后端推送指标、告警、终端输出。终端数据经
`/ws/monitor` ↔ 后端中继 ↔ `/ws/agent`。

### 3.4 可靠消息与告警

指标/告警事件先写 `message_outbox`（事务内），再由发布器投递 RabbitMQ：
exchange `cloide.events`、死信 `cloide.dlx`，业务队列
`cloide.alert.metrics` / `cloide.alert.triggered` / `cloide.alert.resolved`
及对应 DLQ。消费侧以 `message_consume_records (consumer, event_id)` 唯一键
保证幂等；失败按 2^attempts 退避重试，耗尽转 DLQ。拓扑见
`docs/protocol/rabbitmq-topology-v1.md`。

### 3.5 Redis（可选）

`REDIS_ENABLED=false` 时单实例无状态运行。开启后用于 Monitor ticket 共享、
JWT 登出黑名单（TTL=剩余有效期）、登录防爆破计数；多实例部署需要开启。

### 3.6 AI

基于 Spring AI 1.0.0 的 OpenAI 兼容客户端，默认全关（`AI_ENABLED=false`）。
能力：只读诊断、运维问答（只读工具）、告警解释、定时健康报告、命令域
（白名单模板 + 审批/自动审批 + 审计）。Provider 只接收脱敏白名单摘要，
不读取终端/SSH 数据；默认只允许 HTTPS endpoint。迁移到华为云 MaaS 时替换
`AI_BASE_URL`/`AI_API_KEY`/`AI_MODEL` 即可，代码层不变。

## 4. 安全边界

- JWT HS256；`JWT_SECRET` 必须由环境变量提供；`AES_GCM_KEY` 用于 SSH 凭据等敏感字段加密。
- RBAC：`admin` 与 `approved` 两级；管理接口校验角色；注册需审核。
- 首管理员一次性初始化令牌：空库注册必须携带，避免公网「先到先得」。
- 限流：登录/注册、Agent 消息、终端消息、AI 请求分别限流。
- SSH：主机指纹登记/校验、CIDR 出站白名单、并发上限、超时。
- 终端：输入/输出字节上限、速率限制、空闲/最大生命周期。
- AI：提示词注入防护、字段白名单脱敏、按管理员/天的 token 预算。
- 凭据只走环境变量或部署配置；日志脱敏；任何真实值不入库。

## 5. 部署形态

- **systemd + Nginx**：`deploy/server/` 提供 install.sh、unit、env 模板、
  `nginx.conf.example`；后端只监听 `127.0.0.1:18080`，Nginx 反代 REST 与两个 WS 路径。
- **Docker Compose**：根 `docker-compose.yml` 编排 mysql/rabbitmq/redis/server，
  `agent` 为可选 profile；没有 web 服务，API 通过 `API_PORT` 发布。
- 本地开发：`server/` 用 Maven Wrapper；`agent/` 用 Go 1.23；`android/` 用
  Gradle Wrapper + Android SDK 36。

## 6. 版本基线（迁移期）

| 层 | 当前 | 仓库目标 |
|---|---|---|
| 后端 | Spring Boot 3.4.13 / MyBatis-Plus 3.5.12 / springdoc 2.8.9 / Spring AI 1.0.0 | Boot 4.1.2 / MP 3.5.18 / springdoc 3.2.0 |
| 契约 | OpenAPI 3.0.3 | OpenAPI 3.1.0 |
| 数据库 | MySQL 8.4（本地）/ RDS MySQL 8.0（云） | RDS MySQL 8.0 |
| 队列/缓存 | RabbitMQ 4.3 / Redis 7 | 未锁版本 |
| Agent | Go 1.23 | 未约定 |
| Android | Kotlin 2.0.21 / AGP 8.13.0 / Gradle 8.13 / compileSdk 36 | 未约定 |

迁移期不升级版本；升级另开 PR，详见
`.agents/notes/implemented/architecture/2026-10-29-cloide-shell-migration.md`。

## 7. 相关文档

- 接口契约：`docs/openapi/README.md`
- WS / 消息 / RabbitMQ 协议：`docs/protocol/`
- 数据库设计：`docs/tech-summary/database-design.md`
- 部署与运维：`docs/ops-manual/`
