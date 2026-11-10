# Agent 笔记：华为云 DevSpace 盒子部署拓扑

状态：已实现

## 先说结论

cloide 目前单机部署在华为云 DevSpace 盒子（openEuler 22.03 aarch64）上：MySQL 8.0、RabbitMQ 3.9、Redis 4.0、后端和 Agent 全部只监听 127.0.0.1，数据与配置放在 `/root/cloide` 持久盘。公网入口复用 `genhaosan.online` 的证书，通过 `/cloide/` 路径反代到公网机的 SSH 反向隧道，再进入盒子 18080。数据库继续用 MySQL，不在赛前切 PostgreSQL。

## 问题

比赛要求应用真实部署在华为云并可复现演示；DevSpace 盒子没有 systemd、没有 Docker，`/root` 是唯一持久盘（60G ext4），且容器启动脚本会把 `/root` 重置为 750，普通服务用户无法穿越。需要在不破坏盒子既有 DevSpace 服务的前提下，把 cloide 的完整链路跑起来，并给出可公网访问的 HTTPS/WSS 入口。

## 决策

- **组件**：MySQL 8.0.37、RabbitMQ 3.9.10、Redis 4.0.14、`cloide-server`、`cloide-agent`，全部只监听 127.0.0.1；端口 3306 / 5672 / 6379 / 18080。
- **持久化**：JAR、Agent、配置、日志、MySQL 数据、Redis AOF 都放 `/root/cloide`；容器重建后仍保留。
- **MySQL 运行方式**：以 `--user=root` 运行。原因是容器不允许 bind mount，`/root` 启动时被重置为 750，`mysql` 用户无法穿越；这是该 DevSpace 环境的例外，密码仍只存 600 文件。
- **RabbitMQ**：以 `rabbitmq` 用户运行，数据在 `/var/lib/rabbitmq`（overlay，瞬态）；vhost/user 由 `start-all.sh` 幂等重建，消息本体是瞬态的，Outbox 在 MySQL 中。
- **启动**：`/root/cloide/start-all.sh` 幂等拉起全部组件；当前容器的 `/start_docker.sh` 已加延迟 30s 的钩子。容器重建导致钩子丢失时，手动执行 `start-all.sh`。
- **公网入口**：公网机 nginx 在 `genhaosan.online` 的 443 server 块增加 `/cloide/` 与 `/cloide/ws/`，反代到 `127.0.0.1:18082`；盒子用 `ssh -R 127.0.0.1:18082:localhost:18080` 建隧道。旧站 `/api/`、`/ws/` 不受影响。`start-all.sh` 在 222/18082 隧道缺失时会重建，容器重启后公网入口可自愈。
- **数据库选型**：继续 MySQL；不切 PostgreSQL。

### 正确做法

- 部署步骤以仓库 `docs/ops-manual/华为云DevSpace-部署手册.md` 为准；盒子上的 `/root/cloide/DEPLOY.md` 只是运行机副本。
- 新组件或新端口先确认 127.0.0.1 监听，再决定是否经隧道/nginx 暴露。
- 修改 `/root/cloide/config/*.env` 后重启对应进程；`start-all.sh` 只补缺失进程，不会重启已健康的服务。
- 公网入口变更前备份 `/www/server/panel/vhost/nginx/susumonitor.conf`，`nginx -t` 通过后再 reload。

### 不要这样做

- 不要把部署步骤只留在盒子本地；仓库手册缺失会导致盒子重建后无法复现。
- 不要把 MySQL/RabbitMQ/Redis 绑定到 0.0.0.0；不要把它们暴露到公网。
- 不要把 `server.env`、`agent.env`、`secrets.env`、`admin-credentials.txt` 提交到仓库或发到聊天。
- 不要在没有备份和 `nginx -t` 的情况下改公网机 nginx；那台机还承载其他站点。

## 考虑过的备选方案

### 备选方案一：华为云 RDS MySQL + Redis

更符合“使用华为云托管产品”的叙事，也省去本地运维；但需要实例与连接信息，且当前盒子已能本地跑通。先本地，后续可切。

### 备选方案二：PostgreSQL

PostgreSQL 在复杂查询和扩展性上更好，但本项目有 37 个 MySQL 专用 Flyway 迁移、MyBatis SQL 和 875 个测试；切换需要重写 schema/SQL 并重新验证，赛前风险高。暂不采用。

### 备选方案三：Docker Compose / systemd

Compose 和 systemd 都是仓库既有部署方式，但盒子没有 Docker，PID1 是 tini，没有 systemd；不可用。

## 后果

- 收益：单机可跑、数据持久、公网 HTTPS/WSS 入口复用现有证书，Android 端可用路径前缀接入。
- 代价：MySQL 以 root 运行；RabbitMQ 数据瞬态；盒子重建后可能需要手动跑 `start-all.sh`；本地 MySQL/Redis 不是华为云托管产品，赛前若要强调 RDS/Redis 需另做迁移。
- 触发重新评估：切 RDS/Redis、盒子重建、需要多实例、需要 PostgreSQL、公网入口改为新子域。

## 验证

- `ssh susumonitor-box 'bash /root/cloide/start-all.sh'`
- `curl -fsS https://genhaosan.online/cloide/api/health`
- `curl -fsS https://genhaosan.online/cloide/api/ready`
- `cd server && ./mvnw test`

## 适用范围

- `server/`
- `deploy/`
- `docker-compose.yml`
- `docs/ops-manual/华为云DevSpace-部署手册.md`
- `docs/logs/20261109-华为云DevSpace部署.md`
- `.agents/skills/hwict-dev/SKILL.md`
