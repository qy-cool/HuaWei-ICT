# 华为云 DevSpace 部署手册（cloide）

**适用范围**：华为云 DevSpace 容器（openEuler 22.03 LTS，aarch64，7C/12G，无 systemd、无 Docker，`/root` 为持久盘）上从零部署 cloide 后端、Go Agent 与 MySQL/RabbitMQ/Redis，并通过公网机 SSH 反向隧道 + nginx 暴露 HTTPS/WSS。
**不适用**：有 systemd 或 Docker 的常规服务器，请使用《[cloide-shell Server 部署安装手册](./cloide-shell-Server-部署安装手册.md)》。
**脱敏约定**：`<PUBLIC_HOST>`、`<PUBLIC_DOMAIN>`、`<DB_PASSWORD>`、`<JWT_SECRET>` 等均为占位符；真实值只写入目标机 600 权限 env 文件，不入库、不进聊天。

---

## 一、拓扑与端口

```text
Android App / API 客户端
  │ HTTPS / WSS
  ▼
公网机 nginx（443，TLS 终止）
  ├─ /cloide/      → 127.0.0.1:18082
  └─ /cloide/ws/   → 127.0.0.1:18082（Upgrade）
        │ SSH 反向隧道（盒子主动连接公网机）
        ▼
DevSpace 盒子 127.0.0.1:18080
  ├─ cloide-server（Spring Boot）
  ├─ MySQL 127.0.0.1:3306
  ├─ RabbitMQ 127.0.0.1:5672
  ├─ Redis 127.0.0.1:6379
  └─ cloide-agent → ws://127.0.0.1:18080/ws/agent
```

| 组件 | 版本（DevSpace 实测） | 端口 | 说明 |
|---|---|---|---|
| cloide-server | 0.0.1-SNAPSHOT（Spring Boot 3.4.13） | 18080 | JAR 运行，仅 127.0.0.1 |
| cloide-agent | linux-arm64 | — | 主动连后端 WebSocket |
| MySQL | 8.0.37（发行版包） | 3306 | 数据在 `/root/cloide/mysql-data` |
| RabbitMQ | 3.9.10（发行版包） | 5672 / 25672 | 数据在 overlay，瞬态 |
| Redis | 4.0.14（发行版包） | 6379 | AOF 在 `/root/cloide/redis-data` |

> 版本说明：DevSpace 镜像提供的是发行版 MySQL 8.0 / RabbitMQ 3.9 / Redis 4.0，低于仓库目标基线（MySQL 8.4、RabbitMQ 4.x）。当前链路已按实测版本跑通；若比赛要求更高版本，优先迁华为云 RDS/Redis，不在盒子内原地升级。

## 二、前置条件

- 盒子以 `root` 操作；`/root` 至少有 40G 可用空间，且为持久盘。
- JDK 21 已安装（本环境为 `/usr/local/java/bin/java`）；`curl`、`ss`、`pgrep`、`sha256sum` 可用。
- MySQL / RabbitMQ / Redis 的 aarch64 RPM 包已上传到盒子。
- 公网机满足：
  - nginx 持有 `<PUBLIC_DOMAIN>` 的证书；
  - sshd 允许反向隧道，`GatewayPorts clientspecified`（222 端口需要）；
  - 18082 只监听 `127.0.0.1`，不直接暴露公网。
- 构建机：JDK 21、Maven Wrapper、Go 1.2x。

## 三、构建与上传

```bash
# 后端
cd server
./mvnw clean package -DskipTests
# 产出 server/target/cloide-server-0.0.1-SNAPSHOT.jar

# Agent（DevSpace 是 aarch64，必须 arm64）
cd agent
GOOS=linux GOARCH=arm64 CGO_ENABLED=0 go build -o bin/cloide-agent-linux-arm64 ./cmd/cloide-agent
```

受限 shell 下 `scp` 可能被拒绝（`Connection closed`）。可改用标准输入写入并校验：

```bash
cat server/target/cloide-server-0.0.1-SNAPSHOT.jar \
  | ssh <DEVSPACE_HOST> 'cat > /root/cloide/server/cloide-server.jar'
sha256sum server/target/cloide-server-0.0.1-SNAPSHOT.jar
ssh <DEVSPACE_HOST> 'sha256sum /root/cloide/server/cloide-server.jar'
```

两侧哈希一致后再启动。

## 四、安装依赖组件

### 4.1 MySQL 8.0

overlay 文件系统不支持 `cap_set_file`，直接 `rpm -ivh` 会在安装脚本阶段失败。使用：

```bash
rpm -ivh --noscripts --nocaps --replacepkgs mysql-*.rpm
```

初始化与启动：

```bash
BASE=/root/cloide
mkdir -p "$BASE/mysql-data" "$BASE/run" "$BASE/logs"

mysqld --initialize-insecure --user=root --datadir="$BASE/mysql-data"

mysqld --user=root --datadir="$BASE/mysql-data" \
  --socket="$BASE/run/mysql.sock" --port=3306 --bind-address=127.0.0.1 \
  --pid-file="$BASE/run/mysqld.pid" --log-error="$BASE/logs/mysqld.log" --daemonize
```

> **为什么以 root 运行**：容器不允许 bind mount，且 `/root` 在容器启动时被重置为 750，`mysql` 用户无法穿越。这是该 DevSpace 环境的例外；数据库密码仍只存 600 文件。

创建库与专用账号：

```sql
CREATE DATABASE IF NOT EXISTS cloide
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS 'cloide'@'127.0.0.1' IDENTIFIED BY '<DB_PASSWORD>';
GRANT ALL PRIVILEGES ON cloide.* TO 'cloide'@'127.0.0.1';
FLUSH PRIVILEGES;
```

Flyway 会在后端首次启动时自动应用 `V1-V37`。已执行的迁移脚本不得修改，只能新增版本。

### 4.2 RabbitMQ 3.9

安装 Erlang 与 RabbitMQ RPM 后启动：

```bash
RABBITMQ_ALLOW_ROOT=1 nohup /usr/sbin/rabbitmq-server \
  > /var/log/rabbitmq/startup.log 2>&1 &
```

服务进程由包脚本降权为 `rabbitmq` 用户。`rabbitmqctl` 必须以该用户执行，否则会因 cookie 权限失败：

```bash
CTL='HOME=/var/lib/rabbitmq LANG=C.UTF-8 /usr/sbin/rabbitmqctl'
su -s /bin/bash rabbitmq -c "$CTL add_vhost cloide" || true
su -s /bin/bash rabbitmq -c "$CTL add_user cloide '<RABBITMQ_PASSWORD>'" || true
su -s /bin/bash rabbitmq -c "$CTL set_permissions -p cloide cloide '.*' '.*' '.*'" || true
```

RabbitMQ 数据在 `/var/lib/rabbitmq`（overlay，瞬态）。`start-all.sh` 每次幂等重建 vhost/用户；消息本体是瞬态的，Outbox 在 MySQL 中。

### 4.3 Redis 4.0

```bash
redis-server --daemonize yes --bind 127.0.0.1 --port 6379 \
  --requirepass '<REDIS_PASSWORD>' \
  --dir /root/cloide/redis-data --appendonly yes \
  --logfile /root/cloide/logs/redis.log
```

Redis 用于 JWT 登出黑名单、Monitor Ticket 与限流；DevSpace 的 4.0 版本满足当前功能，赛前如需高可用再迁华为云 Redis。

## 五、目录与配置

| 路径 | 用途 |
|---|---|
| `/root/cloide/server/cloide-server.jar` | 后端 JAR |
| `/root/cloide/agent/cloide-agent` | Agent 二进制 |
| `/root/cloide/config/server.env` | 后端环境变量（600） |
| `/root/cloide/config/agent.env` | Agent 环境变量（600） |
| `/root/cloide/config/secrets.env` | MySQL root / RabbitMQ / Redis 密码（600） |
| `/root/cloide/config/admin-credentials.txt` | 首管理员账号密码（600，仅留档） |
| `/root/cloide/logs/` | server / agent / mysqld / redis / start-all 日志 |
| `/root/cloide/run/` | pid 与 socket |
| `/root/cloide/mysql-data/`、`/root/cloide/redis-data/` | 持久数据 |

关键后端变量（完整清单以 `server/src/main/resources/application.yml` 为准）：

| 变量 | 示例/说明 |
|---|---|
| `SERVER_ADDRESS` / `SERVER_PORT` | `127.0.0.1` / `18080` |
| `DB_HOST` / `DB_PORT` / `DB_NAME` | `127.0.0.1` / `3306` / `cloide` |
| `DB_USER` / `DB_PASSWORD` | `cloide` / `<DB_PASSWORD>` |
| `SPRING_RABBITMQ_HOST` / `PORT` / `USERNAME` / `PASSWORD` / `VIRTUAL_HOST` | `127.0.0.1` / `5672` / `cloide` / `<RABBITMQ_PASSWORD>` / `cloide` |
| `REDIS_ENABLED` / `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | `true` / `127.0.0.1` / `6379` / `<REDIS_PASSWORD>` |
| `JWT_SECRET` / `AES_GCM_KEY` | `openssl rand -base64 32` 生成，至少 32 字节 |
| `AUTH_BOOTSTRAP_TOKEN` | 可选；空库首管理员注册的一次性令牌 |
| `OUTBOX_ENABLED` | 默认 `true`；RabbitMQ 不可用时设为 `false` 降级 |
| `CORS_ALLOWED_ORIGINS` | 当前无 Web 前端，保持空 |
| `AI_ENABLED` / `AI_QA_ENABLED` / `AI_EXPLANATION_ENABLED` / `AI_REPORT_ENABLED` | 默认 `false`；MaaS 接入后由 `enable-ai.sh` 打开 |
| `AI_COMMAND_ENABLED` | 保持 `false`，命令执行默认关闭 |

Agent 变量：

| 变量 | 说明 |
|---|---|
| `CLOIDE_BACKEND_URL` | 本机部署用 `ws://127.0.0.1:18080/ws/agent`；公网部署用 `wss://<PUBLIC_DOMAIN>/ws/agent` |
| `CLOIDE_SERVER_ID` | 后端服务器记录 ID |
| `CLOIDE_AGENT_TOKEN` | 后端发放的 Agent Token |
| `CLOIDE_METRICS_BUFFER_PATH` | 离线缓冲路径，如 `/root/cloide/run/metrics-buffer.json` |

所有 env 文件执行 `chmod 600`。不要把 `server.env`、`agent.env`、`secrets.env`、`admin-credentials.txt` 提交到仓库或发到聊天。

## 六、启动与停止

`start-all.sh` 是幂等入口，按以下顺序补齐缺失组件：

1. `source secrets.env`；
2. MySQL：`mysqladmin ping` 失败则 `mysqld --daemonize` 启动，最多等 60s；
3. RabbitMQ：5672 未监听则启动，随后幂等创建 vhost/用户/权限；
4. Redis：`redis-cli ping` 失败则按 4.3 启动；
5. 后端：`/api/health` 失败则加载 `server.env` 后 `nohup java -jar ...`，最多等 180s；
6. Agent：pid 不存在或进程已退出则加载 `agent.env` 后 `nohup` 启动；
7. SSH 反向隧道：222 与 18082 缺失则重建。

```bash
bash /root/cloide/start-all.sh
curl -fsS http://127.0.0.1:18080/api/health
curl -fsS http://127.0.0.1:18080/api/ready
tail -f /root/cloide/logs/start-all.log
```

容器启动时 `/start_docker.sh` 会延迟 30s 调用一次 `start-all.sh`（当前容器已加钩子）。容器重建导致钩子丢失时，手动执行 `start-all.sh`。

停止（演示期间不建议）：

```bash
kill "$(cat /root/cloide/run/agent.pid)"
kill "$(cat /root/cloide/run/server.pid)"
REDISCLI_AUTH='<REDIS_PASSWORD>' redis-cli -h 127.0.0.1 -p 6379 shutdown
mysqladmin --socket=/root/cloide/run/mysql.sock -uroot -p shutdown
```

> `mysqladmin -p` 不跟密码时会交互式提示，避免密码进入 shell 历史或进程列表；Redis 用 `REDISCLI_AUTH` 同理。

> 进程守护：`start-all.sh` 只在容器启动钩子或手动执行时运行，server/agent/隧道崩溃后不会自动拉起。若镜像提供 cron，可加 `*/2 * * * * bash /root/cloide/start-all.sh >/dev/null 2>&1` 作为演示期兜底；没有 cron 时需在演示前人工确认。

## 七、公网入口

### 7.1 SSH 反向隧道

盒子主动连接公网机：

```bash
ssh -i /root/.ssh/<KEY> -N -f -o LogLevel=ERROR \
  -R 0.0.0.0:222:localhost:22 \
  -o ExitOnForwardFailure=yes -o ServerAliveInterval=30 root@<PUBLIC_HOST>

ssh -i /root/.ssh/<KEY> -N -f -o LogLevel=ERROR \
  -R 127.0.0.1:18082:localhost:18080 \
  -o ExitOnForwardFailure=yes -o ServerAliveInterval=30 root@<PUBLIC_HOST>
```

- 222 用于公网机 SSH 回连盒子；公网机 sshd 需 `GatewayPorts clientspecified`。
- 18082 只绑定公网机回环，由 nginx 反代，不直接暴露。

### 7.2 nginx

在 `<PUBLIC_DOMAIN>` 的 443 server 块加入（先备份，`nginx -t` 通过后 reload）：

```nginx
location /cloide/ws/ {
    proxy_pass http://127.0.0.1:18082/ws/;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto https;
    proxy_read_timeout 3600s;
    proxy_send_timeout 3600s;
}

location /cloide/ {
    proxy_pass http://127.0.0.1:18082/;
    proxy_http_version 1.1;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto https;
    proxy_read_timeout 300s;
    proxy_send_timeout 300s;
}
```

不要改动公网机既有站点的 `/api/`、`/ws/` 路径；变更前备份 vhost 配置。

## 八、MaaS 接入

1. `cp /root/cloide/config/maas.env.example /root/cloide/config/maas.env`；
2. 填入 `AI_BASE_URL`、`AI_API_KEY`、`AI_MODEL`；
3. `bash /root/cloide/enable-ai.sh`；
4. 脚本会设置 `AI_ENABLED=true`、`AI_QA_ENABLED=true`、`AI_EXPLANATION_ENABLED=true`、`AI_REPORT_ENABLED=true`，保持 `AI_COMMAND_ENABLED=false`，重启后端并打印 health。

`AI_API_KEY` 只存在 `maas.env`（600），不写入 `server.env`、不提交仓库；`AI_ALLOW_INSECURE_HTTP` 保持 `false`，MaaS 地址必须是 HTTPS。

## 九、验收清单

| # | 检查项 | 命令/预期 |
|---|---|---|
| 1 | 后端健康 | `curl -fsS http://127.0.0.1:18080/api/health` → UP |
| 2 | 依赖就绪 | `curl -fsS http://127.0.0.1:18080/api/ready` → UP |
| 3 | Flyway | `server.log` 中 37 个迁移全部 success |
| 4 | 首管理员状态 | `GET /api/auth/bootstrap-status` → `bootstrapPending=false`（已初始化） |
| 5 | 登录 | `POST /api/auth/login` → 200 + JWT |
| 6 | WebSocket 鉴权 | 无 ticket 连接 `/ws/monitor` → 401 |
| 7 | Agent 在线 | 服务器列表 `build-box` 在线，指标持续上报 |
| 8 | 公网入口 | `curl -fsS https://<PUBLIC_DOMAIN>/cloide/api/health` → UP |
| 9 | 旧站无回归 | 公网机既有站点健康检查仍为 UP |

## 十、故障排查

| 现象 | 原因 | 处理 |
|---|---|---|
| `rpm` 报 `cap_set_file failed` | overlay 不支持文件 capability | `rpm -ivh --noscripts --nocaps --replacepkgs ...` |
| `mount` bind mount 被拒绝 | 容器禁止 bind mount | 不用挂载，数据直接放 `/root/cloide` |
| `mysqld` 无法启动 | `/root` 权限或 socket 目录缺失 | 以 `--user=root` 启动，先建 `run/`、`logs/` |
| `rabbitmqctl` cookie 错误 | 以 root 执行，读不到 rabbitmq 的 erlang cookie | `su -s /bin/bash rabbitmq -c 'HOME=/var/lib/rabbitmq LANG=C.UTF-8 rabbitmqctl ...'` |
| `scp` 报 `Connection closed` | 受限 shell 不支持 scp 远程命令 | `cat file \| ssh ... 'cat > ...'` + `sha256sum` |
| 公网 502/超时 | 隧道断开或 nginx 上游不对 | 查 222/18082 监听，重跑 `start-all.sh`，`nginx -t` |
| 端口冲突 | 旧进程未退出 | `ss -tlnp` 定位后停旧进程 |
| 首管理员注册 500 | 令牌消费顺序/令牌状态异常 | 查 `server.log`；确认 `auth_bootstrap_state.admin_initialized=0` 且令牌未消费；参见 `.agents/notes/implemented/bug-fix/2026-11-09-bootstrap-register-order.md` |

> 远程执行 `pkill -f <模式>` 时，模式可能匹配到当前 SSH 命令行本身，导致会话被杀。优先用锚定模式（如 `^ssh`）或 `start-all.sh` 恢复。

## 十一、安全与备份

- 所有依赖组件只监听 `127.0.0.1`；公网只经 nginx 443 与 SSH 隧道进入。
- 凭据文件 600；日志不输出密码、Token、API Key；仓库与聊天不出现真实凭据。
- `start-all.sh` 的 `redis-server --requirepass` 与 `mysqladmin -p` 会把密码短暂放进进程参数；单用户容器可接受，多用户/合规场景改用 600 权限的 `redis.conf` 与 MySQL `--defaults-extra-file`。
- 备份：`mysqldump` 导出 `cloide` 库 + 打包 `/root/cloide/config` 与 `/root/cloide/redis-data`；恢复流程见《[cloide-shell 备份与恢复手册](./cloide-shell-备份与恢复手册.md)》。
- 迁移到华为云 RDS/Redis 时，只需替换 `server.env` 的连接变量并重启后端；Flyway 迁移保持只新增。
