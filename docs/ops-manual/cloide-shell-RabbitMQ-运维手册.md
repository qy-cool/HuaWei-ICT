# cloide-shell RabbitMQ 运维手册（MVP-8）

**适用范围**：MVP-10 Outbox 引入 + MVP-11 消费侧的 RabbitMQ 依赖的日常运维。
**相关**：旧项目开发日志、旧项目开发日志（实施与验收）、`docs/protocol/rabbitmq-topology-v1.md`（冻结拓扑）。

---

## 一、架构位置（为什么需要 RabbitMQ）

```text
Agent 上报 metrics.report
  -> MySQL 事务：指标落库 + message_outbox(pending) 同事务写入
  -> OutboxPublisherScheduler（200ms 默认轮询，FOR UPDATE SKIP LOCKED）
  -> Publisher Confirm 投递到 cloide.events
  -> cloide.alert.metrics 队列
  -> AlertMessageConsumer（AUTO 确认 + 幂等 + 有限重试；默认单消费者，可配置并发 `ALERT_CONSUMER_CONCURRENCY`；不可重试/重试耗尽 -> DLQ）
```

- RabbitMQ 承载 **metrics.reported.v1 事件投递**；Agent/Monitor WebSocket、MySQL、SSH/终端链路均不经过它。
- **Broker 停机不影响指标采集与落库**，只影响事件投递（outbox 退避堆积，恢复后自动补发补消费）；**告警评估依赖 Broker**——停机期间告警延迟评估，恢复后补消费（有界，不丢）。
- 消费侧：业务队列消息应**接近 0**（消费即 ACK）；异常消息进入 `cloide.alert.metrics.dlq`。

## 二、vhost / 用户 / 权限管理

```bash
# 查看
rabbitmqctl list_vhosts
rabbitmqctl list_users
rabbitmqctl list_permissions -p cloide

# 创建（部署安装手册 §3.2）
rabbitmqctl add_vhost cloide
rabbitmqctl add_user cloide '<强密码>'
rabbitmqctl set_permissions -p cloide cloide '.*' '.*' '.*'
rabbitmqctl set_user_tags cloide administrator    # 管理台/验收需要；生产可收回为 monitoring

# 轮换密码（需同步更新 server.env 的 SPRING_RABBITMQ_PASSWORD 并重启后端）
rabbitmqctl change_password cloide '<新密码>'

# 删除/禁用
rabbitmqctl delete_user <用户名>
rabbitmqctl clear_permissions -p cloide <用户名>
```

> **历史环境记录（2026-08-02，仅供追溯，禁止用于生产操作）**：本机与云端曾使用 RabbitMQ 4.3.4 验收；验证环境用户、密码、节点地址和卷标识均不在本文记录。当前操作请通过受控环境变量或密钥管理系统注入凭据，并按实际部署核对版本与 vhost。

> 凭据管理：RabbitMQ 密码通过 `server.env` 的 `SPRING_RABBITMQ_PASSWORD` 注入；vhost/用户清单建议随《备份与恢复手册》记录。

## 三、拓扑（2 exchange + 6 队列，幂等声明）

| 类型 | 名称 | 关键参数 |
|---|---|---|
| Topic Exchange | `cloide.events` | durable, non-auto-delete |
| Topic Exchange | `cloide.dlx` | durable, non-auto-delete |
| Queue | `cloide.alert.metrics` | durable；`x-dead-letter-exchange=cloide.dlx`、`x-dead-letter-routing-key=metrics.reported.v1` |
| Queue | `cloide.alert.metrics.dlq` | durable，不自动回投业务队列 |
| Queue | `cloide.alert.triggered` | durable；`x-dead-letter-exchange=cloide.dlx`、`x-dead-letter-routing-key=alert.triggered.v1`；出站告警事件，消费者 `alert-notifier` 已接入（2026-08-12），驱动外部通知排程 |
| Queue | `cloide.alert.triggered.dlq` | durable，不自动回投业务队列 |
| Queue | `cloide.alert.resolved` | durable；`x-dead-letter-exchange=cloide.dlx`、`x-dead-letter-routing-key=alert.resolved.v1`；恢复事件，消费者 `alert-resolved-notifier` 已接入（2026-08-12） |
| Queue | `cloide.alert.resolved.dlq` | durable，不自动回投业务队列 |

- 由 `RabbitMqTopologyConfig` 声明式创建，**broker 重启/升级后自动重建**，无需手工声明。
- 绑定（共 6 条）：`cloide.events -- metrics.reported.v1 --> cloide.alert.metrics`；`cloide.events -- alert.triggered.v1 --> cloide.alert.triggered`；`cloide.events -- alert.resolved.v1 --> cloide.alert.resolved`；DLX → DLQ 同理。

```bash
rabbitmqctl list_queues -p cloide name durable arguments
rabbitmqctl list_exchanges -p cloide name type durable
```

## 四、健康检查与监控

| 项 | 方式 | 说明 |
|---|---|---|
| 后端视角 | `GET /api/ready` | DB + RabbitMQ 双检查；Broker 不可达返回 **HTTP 503 / 50301 "rabbitmq unavailable"**（存活但未就绪，应用不退出） |
| 积压监控 | `rabbitmqctl list_queues -p cloide name messages` | `cloide.alert.metrics` / `cloide.alert.triggered` / `cloide.alert.resolved` 正常应接近 0（消费即 ACK）；**持续增长**说明消费者未运行或评估/通知排程失败（查后端日志与 DLQ）；对应 DLQ 增长 = 数据错误或重试耗尽，需人工介入 |
| 死信处置 | 管理台/管理 API 查看三个 DLQ（`cloide.alert.metrics.dlq` / `cloide.alert.triggered.dlq` / `cloide.alert.resolved.dlq`）；先运行 `node scripts/api-test/replay-dlq.mjs [--event metrics\|alert]`（默认 dry-run、最多 10 条）审查摘要。仅在**本机隔离验证环境**、根因已修复且消息契约有效时，设置 `CLOIDE_VALIDATION_CONFIRM=I_UNDERSTAND_DLQ_REPLAY` 后追加 `--execute --limit=N`（N≤50）。工具只将合规信封原样发布回 `cloide.events`，不会直接写业务队列；非法 JSON/schema/type 等消息拒绝重放。**生产环境禁止执行本工具及 `--purge`，必须按变更流程人工审查、备份并使用受控运维工具。**
| 管理台 | `http://127.0.0.1:15672`（仅本机/受控内网，禁止公网暴露） | 队列/连接/节点监控 |
| Broker 状态 | `rabbitmqctl status` | 节点/版本/Erlang 版本 |

## 五、停机影响矩阵与恢复

| 场景 | 影响 | 恢复行为 |
|---|---|---|
| Broker 停机 | 指标照常落库；outbox 事件退避堆积（指数退避封顶 300s）；`/api/ready` 50301；**告警评估暂停**（消费侧无消息可消费） | Broker 恢复后发布器**自动补发**，消费者自动补消费并评估（MVP-10 三阶段 + MVP-11 验收 PASS 证据），无需人工干预 |
| Broker 数据目录损坏 | 消息丢失（队列中未消费事件）；**指标数据不受影响**（已落 MySQL） | 重建 broker → 拓扑自动声明 → 后续事件正常投递；丢失的队列事件需接受（已评估过的无损失；未评估的指标数据仍在 MySQL，可后续重放或接受） |
| 后端重启 | 无影响 | 发布器随应用恢复轮询；Broker 侧连接自动重连 |

## 六、生产部署建议

1. **系统服务方式**（官方 Windows 服务 / Linux 包管理安装），**不要用本机验收的免安装前台方式**（`local/rmq` 仅限开发机验收，旧项目开发日志 已声明）。
2. **版本匹配**：RabbitMQ 4.3 必须配 Erlang 27.x；**Erlang 29 不兼容**（horus 机制启动失败，实测记录）。升级前查官方版本矩阵。
3. 备份与恢复：RabbitMQ 配置/用户清单按《备份与恢复手册》记录；消息队列本身不做业务级备份（指标数据在 MySQL，消息是短暂的投递载体）。
