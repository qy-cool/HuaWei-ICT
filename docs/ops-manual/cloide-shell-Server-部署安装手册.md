# cloide-shell Server 部署安装手册

**适用范围**：Java 后端 + 依赖组件（MySQL / RabbitMQ / Nginx）从零到上线的安装与启动操作。当前没有 Web 前端；客户端为 Android App 或直接调用 REST/WebSocket。
**配套资产**：`deploy/server/`（install.sh、systemd unit、nginx 模板、env 模板、backup/restore）。
**Go Agent 安装**：见 `docs/ops-manual/Go-Agent 部署使用手册.md`。

---

## 一、组件拓扑与职责

```text
Android App / API 客户端
  │ HTTPS / WSS（生产）；HTTP 仅限本机或受控内网
  ▼
Nginx（80/443，TLS 终止）
  ├─ /api/       → 127.0.0.1:18080（REST）
  ├─ /api-docs、/swagger-ui.html → 127.0.0.1:18080（接口文档；prod 默认关闭）
  ├─ /ws/agent   → 127.0.0.1:18080（Agent 通道）
  └─ /ws/monitor → 127.0.0.1:18080（监控推送）

Java 后端（cloide-server，systemd，127.0.0.1:18080）
  ├─ MySQL 8.4（127.0.0.1:3306，Flyway 管理迁移 V1-V37）
  └─ RabbitMQ（127.0.0.1:5672，vhost cloide，Outbox/告警事件）

Go Agent（部署到被监控服务器）
  └─ wss://SERVER_IP_OR_DOMAIN/ws/agent（Token 鉴权 + 指标上报 + 终端中继）
```

## 二、前置条件与版本要求

| 组件 | 版本要求 | 说明 |
|---|---|---|
| JDK | 21 LTS | 后端编译与运行 |
| MySQL | 8.4（本地）；华为云 RDS 8.0 | Flyway V1-V37；`utf8mb4`；专用账号最小权限 |
| RabbitMQ | 4.3.x + Erlang 27.x | RabbitMQ 4.3 与 Erlang 29 不兼容（horus），按官方支持矩阵选 Erlang 27 |
| Nginx | 1.24+ | 反代 REST/WS；不再需要 SPA fallback |
| Node.js | 不需要 | 当前没有 Web 前端；后续 React 前端建立后再引入 |

## 三、从零安装流程

### 3.1 MySQL 初始化与账号

```sql
CREATE DATABASE IF NOT EXISTS cloide DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS 'cloide'@'localhost' IDENTIFIED BY '<强密码>';
GRANT ALL PRIVILEGES ON cloide.* TO 'cloide'@'localhost';
FLUSH PRIVILEGES;
```

> 账号仅限 `localhost`；数据库密码通过 `server.env` 注入，不入库、不落日志。

### 3.2 RabbitMQ vhost 与用户

```bash
rabbitmqctl add_vhost cloide
rabbitmqctl add_user cloide '<强密码>'
rabbitmqctl set_permissions -p cloide cloide '.*' '.*' '.*'
rabbitmqctl set_user_tags cloide administrator
```

> 拓扑（2 个 exchange `cloide.events` / `cloide.dlx`，6 个队列及 DLQ）由后端启动时幂等声明，无需手工创建。详见《RabbitMQ 运维手册》。

### 3.3 构建发布包

```bash
cd server
./mvnw clean package -DskipTests
# 产出 target/cloide-server-0.0.1-SNAPSHOT.jar
```

### 3.4 上传与首次安装

```bash
# 1) 上传 JAR 到发布目录
sudo install -d -o root -g root /opt/cloide/releases/RELEASE_ID
sudo install -m 0644 -o root -g root target/cloide-server-0.0.1-SNAPSHOT.jar \
  /opt/cloide/releases/RELEASE_ID/

# 2) 首次安装（脚本会创建 cloide 用户、目录、env 模板与 systemd unit）
sudo RELEASE_ID=RELEASE_ID \
  JAR_PATH=/opt/cloide/releases/RELEASE_ID/cloide-server-0.0.1-SNAPSHOT.jar \
  bash deploy/server/install.sh

# 3) 编辑 /etc/cloide/server.env（至少填 DB_PASSWORD、JWT_SECRET、AES_GCM_KEY）
sudo install -m 0600 -o root -g root \
  deploy/server/cloide-server.env.example /etc/cloide/server.env
sudo editor /etc/cloide/server.env
sudo systemctl restart cloide-server
```

`/etc/cloide/server.env` 关键变量（**密钥不得入库、不得落日志**）：

| 变量 | 说明 |
|---|---|
| `DB_HOST/DB_PORT/DB_NAME/DB_USER/DB_PASSWORD` | 数据库连接 |
| `DB_USE_SSL` | 华为云 RDS 建议 `true`；本地 MySQL 无证书时保持 `false` |
| `JWT_SECRET` / `AES_GCM_KEY` | **必须备份**（丢失 = SSH 凭据密文永久不可解密） |
| `SERVER_ADDRESS=127.0.0.1` `SERVER_PORT=18080` | 生产仅回环监听，由 Nginx 反代 |
| `SPRING_RABBITMQ_*` | RabbitMQ 连接（host/port/virtual-host/username/password） |
| `AGENT_TRUSTED_PROXY_CIDRS` | 反向代理信任列表（生产默认仅本机） |
| `CORS_ALLOWED_ORIGINS` | 浏览器来源白名单；当前没有 Web 前端，留空即可 |
| `ALERT_NOTIFICATION_ENABLED` | 告警外部通知总开关，默认 `false` |
| `MAIL_*` / `ALERT_MAIL_FROM` | 邮件告警 SMTP（不配置则邮件通道自动跳过） |
| `AUTH_BOOTSTRAP_TOKEN` | 首管理员一次性初始化令牌（可选，见 §五） |

> 完整环境变量清单以 `server/src/main/resources/application.yml` 为准。

### 3.5 Nginx 合并

按 `deploy/server/nginx.conf.example` 合并到站点 server 块：
- `/api/` 反代 `127.0.0.1:18080`；
- `/api-docs`、`/swagger-ui.html`、`/swagger-ui/` 反代后端（prod 默认关闭）；
- `/ws/agent`、`/ws/monitor` 反代，带 `Upgrade`/`Connection` 头与 3600s 超时；
- **没有前端静态资源，不配置 `try_files ... /index.html`**；
- 生产必须启用 HTTPS/WSS；HTTP 仅用于本机或证书签发前调试。

## 四、启动 / 停止 / 重启 / 状态

```bash
sudo systemctl enable --now cloide-server
sudo systemctl start cloide-server
sudo systemctl stop cloide-server
sudo systemctl restart cloide-server
sudo systemctl status cloide-server --no-pager
sudo journalctl -u cloide-server -f
```

> 日志不得记录密码/Token/密钥/SSH 凭据；不得为排查 WebSocket 而提高生产日志等级。

## 五、健康检查

| 端点 | 语义 | 期望 |
|---|---|---|
| `GET /api/health` | 进程存活 | `{"status":"UP",...}` |
| `GET /api/ready` | 就绪（DB + RabbitMQ） | `{"status":"UP","database":"ok"}` |
| `GET /api/ready` | RabbitMQ 不可达 | HTTP 503 `50301 rabbitmq unavailable`；应用不退出，恢复后自动补发 |

```bash
curl -s http://127.0.0.1:18080/api/health
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:18080/api/ready   # 期望 200
# 仅 local/dev 或显式开启 springdoc 时可用；prod 默认关闭
# 临时开启（仅内网/调试）：在 server.env 设置
#   SPRINGDOC_API_DOCS_ENABLED=true
#   SPRINGDOC_SWAGGER_UI_ENABLED=true
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:18080/api-docs  # 期望 200
```

## 五点五、首管理员一次性初始化令牌

系统尚未创建首管理员时，`POST /api/auth/register` **必须携带一次性初始化令牌**（缺失 40310 / 不匹配 40311）。首个携带有效令牌注册的用户成为 `admin/approved`，令牌随即作废；此后注册恢复为普通 `user/pending`。

**令牌获取（三选一）：**

1. **自动生成（默认）**：服务启动时若首管理员未初始化且未配置环境变量，自动生成 256-bit 随机令牌并打印到启动横幅。
   ```bash
   sudo journalctl -u cloide-server | grep -A 5 "一次性初始化令牌"
   ```
2. **环境变量预置**：在 `server.env` 设置 `AUTH_BOOTSTRAP_TOKEN`（32-128 字符，`openssl rand -base64 32` 生成）；长度非法会阻止启动。
3. **数据库侧应急**：持有 `AES_GCM_KEY` 时可由运维解密 `auth_bootstrap_state.bootstrap_token_cipher`。

**注册流程**：Android App 注册页输入用户名/密码与初始化令牌 → 注册成功即成为管理员 → 登录并审核后续用户。没有 Web 登录页。

## 六、首次验收清单（部署后必须执行）

| # | 检查项 | 命令/资产 |
|---|---|---|
| 1 | 后端启动 + Flyway V1-V37 迁移成功 | `journalctl -u cloide-server` 无 ERROR |
| 2 | health/ready 正常；local/dev 下 `/api-docs` 可访问 | 见 §五 |
| 3 | RabbitMQ 拓扑已声明 | `rabbitmqctl list_queues` 可见 `cloide.alert.metrics` 等 |
| 4 | 告警全链路 | `node scripts/api-test/verify-alert-ws.mjs`（需验证库 + 管理员账号） |
| 5 | Outbox 三阶段（含停机恢复） | `node scripts/api-test/verify-outbox.mjs` |
| 6 | Agent 连接与指标上报 | `docs/ops-manual/Go-Agent 部署使用手册.md` §七 |
| 7 | Android 登录与监控 | 用 debug 包连到本环境，确认服务器列表与实时指标 |

## 六点五、Docker Compose 部署

容器化部署为 systemd 之外的第二形态：`mysql + rabbitmq + redis + server`，`agent` 为可选 profile；**没有 web 服务**。API 通过 `${API_PORT:-18080}` 发布到宿主机。

### 6.5.1 前置

- Docker Engine ≥ 26 + Compose v2 插件。
- 首次 `docker compose build` 会拉取 Maven/Gradle 依赖，国内网络建议配置镜像。

### 6.5.2 环境文件

```bash
cp .env.example .env
# 必填：MYSQL_ROOT_PASSWORD / DB_PASSWORD / RABBITMQ_PASSWORD
#       JWT_SECRET(≥32B base64) / AES_GCM_KEY(恰32B base64)
# 生成：openssl rand -base64 32（JWT/AES）；openssl rand -hex 16（数据库口令）
```

### 6.5.3 构建与启动

```bash
docker compose build
docker compose --profile agent build agent    # 可选
docker compose up -d
docker compose ps                             # mysql/rabbitmq/redis/server 应 healthy
```

- 入口：`http://<主机>:${API_PORT:-18080}`；生产应在 TLS 终止的 Nginx 入口使用 `https://SERVER_IP_OR_DOMAIN`。
- 数据：命名卷 `mysql-data`/`rabbitmq-data`/`redis-data`/`agent-data`；`docker compose down -v` 清空重置。
- 验收：`node scripts/api-test/verify-docker-compose.mjs`（P0 首管理员 bootstrap + C1 健康/C2 建服务器/C3 WS 订阅/C4 agent token/C5 agent 容器上报）。
- 注意：容器与 systemd 部署不可在同一主机混用端口（3306/5672/18080）。

> 本次迁移只做了 `docker compose config` 结构校验；实机起停需在 Docker 守护进程启动后执行，不得在未运行的情况下声称通过。

## 七、相关手册

- 升级与回滚：《cloide-shell-升级与回滚手册.md》
- 备份与恢复：《cloide-shell-备份与恢复手册.md》
- 安全检查：《cloide-shell-安全检查手册.md》
- RabbitMQ：《cloide-shell-RabbitMQ-运维手册.md》
