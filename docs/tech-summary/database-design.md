# cloide-shell 数据库设计

> 权威 DDL 是 `server/src/main/resources/db/migration/` 下的 Flyway 脚本（当前 V1–V37）。
> 本文按表用途、关键约束与保留策略整理，不替代迁移脚本。

## 1. 约定

- MySQL 8.4（本地）/ 华为云 RDS MySQL 8.0（云上）；`InnoDB`、`utf8mb4`、`utf8mb4_unicode_ci`。
- 表名/字段名小写下划线；索引 `idx_表_字段`，唯一索引 `uk_表_字段`。
- 主键 `BIGINT UNSIGNED AUTO_INCREMENT`；时间列用 `DATETIME`，不用 `TIMESTAMP`。
- 布尔用 `TINYINT(1)`；枚举用 `VARCHAR(20)` 或 `TINYINT`，不用 `ENUM`。
- 不使用外键，关联由应用层维护；查询禁止 `SELECT *`，参数化执行。
- 每个字段必须有 `COMMENT`。**迁移期例外**：V1–V37 为旧项目已执行脚本，部分字段
  缺 `COMMENT`，本轮不改历史脚本，只对新增 V38+ 执行该规范。

## 2. 表分组

| 域 | 表 | 用途 |
|---|---|---|
| 身份 | `users` | 用户账号、密码哈希、角色（admin/user）、审核状态（pending/approved/rejected） |
| 身份 | `auth_bootstrap_state` | 首管理员初始化状态、一次性令牌密文与消费状态 |
| 服务器 | `servers` | 服务器资产、SSH 地址/端口/凭据密文、主机指纹、Agent 标识、软删除 |
| 服务器 | `ssh_test_history` | SSH 连接测试结果与耗时，用于详情页历史 |
| 服务器 | `ssh_sessions` | 历史 SSH 会话表（当前代码未使用；终端以 `terminal_sessions` 为准） |
| 指标 | `metrics` | 固定宽表指标（CPU/内存/磁盘/网络/负载等） |
| 指标 | `metrics_ingestions` | 指标消息幂等记录，拒绝重复/乱序消息 |
| 告警 | `alert_rules` | 告警规则（指标、操作符、阈值、等级、通知渠道），软删除 |
| 告警 | `alert_records` | 告警触发/恢复记录，按服务器/状态/时间窗口查询 |
| 告警 | `alert_states` | 规则 × 服务器当前状态机，防重复触发 |
| 告警 | `alert_notifications` | 每个通知渠道的投递记录与重试状态 |
| 可靠消息 | `message_outbox` | 事务内事件（指标/告警），发布状态与重试 |
| 可靠消息 | `message_consume_records` | 消费幂等记录（consumer + event_id） |
| 终端 | `terminal_sessions` | 终端会话生命周期、用户与 open message 关联 |
| 命令 | `commands` | 历史命令记录表（V4）；当前命令域以 `ai_command_runs` 为准 |
| AI | `ai_diagnostic_runs` | 只读诊断运行记录、模型、状态、错误码、耗时 |
| AI | `ai_qa_runs` | 运维问答运行与审计 |
| AI | `ai_health_reports` | 每日健康报告（摘要/关注点/limitations/事实快照） |
| AI | `ai_alert_explanations` | 单条告警的 AI 解释回看 |
| AI | `ai_command_runs` | 命令域执行记录（模板、参数、审批、结果、审计） |
| AI | `ai_command_auto_approval_policies` | 自动审批策略与观察期配置 |
| AI | `ai_user_provider_configs` | 用户级 AI Provider 配置（endpoint/model/key 密文） |

## 3. 关键约束与幂等

| 约束 | 表 | 作用 |
|---|---|---|
| `uk_users_username` | `users` | 用户名唯一 |
| `uk_servers_host_delete_token` | `servers` | 同一 host 的软删除令牌唯一，支持重复登记 |
| `uk_servers_agent_id` | `servers` | Agent 标识唯一 |
| `uk_alert_rules_active_signature` | `alert_rules` | 未删除规则签名唯一，防重复规则 |
| `uk_alert_states_rule_server` | `alert_states` | 规则 × 服务器状态唯一 |
| `uk_metrics_ingestions_server_message` | `metrics_ingestions` | 指标消息幂等（server_id + message_id） |
| `uk_outbox_event_id` | `message_outbox` | Outbox 事件唯一 |
| `uk_consume_event` | `message_consume_records` | 消费幂等（consumer + event_id） |
| `uk_terminal_sessions_session` | `terminal_sessions` | 终端会话 ID 唯一 |
| `uk_terminal_sessions_user_open_message` | `terminal_sessions` | 同一用户同一 open message 唯一 |
| `uk_ai_command_runs_execution_id` | `ai_command_runs` | 命令执行 ID 唯一 |
| `uk_ai_health_reports_date` | `ai_health_reports` | 每日报告唯一（同日重生成走 UPSERT） |
| `uk_ai_alert_explanations_record` | `ai_alert_explanations` | 单条告警解释唯一 |
| `uk_ai_user_provider_configs_user` | `ai_user_provider_configs` | 用户级 Provider 配置唯一 |

## 4. 保留与清理

默认值来自 `server/src/main/resources/application.yml`，均可用环境变量覆盖：

| 数据 | 默认保留 | 清理方式 |
|---|---|---|
| `metrics` | 10 天 | 每天 03:00 分批清理 |
| `metrics_ingestions` | 7 天 | 每天 03:00 分批清理 |
| `alert_records` | 90 天 | 每天 03:00 分批清理 |
| `alert_notifications` | 90 天 | 每天 03:00 分批清理 |
| `ssh_test_history` | 90 天 | 每天 03:00 分批清理 |
| AI 审计（诊断/问答/命令/解释） | 30 天 | 每天凌晨分批清理 |
| `terminal_sessions` 过期会话 | 按空闲/最大生命周期 | 每小时清理 |
| `message_outbox` / `message_consume_records` | 按清理配置 | 定时分批清理 |

> `JWT_SECRET` 与 `AES_GCM_KEY` 必须纳入备份；丢失后 SSH 凭据等密文不可解密。

## 5. 迁移与变更规则

- 迁移脚本固定放 `server/src/main/resources/db/migration/`，命名 `V{n}__{描述}.sql`。
- **已执行到任何共享环境的脚本不得修改**，只能新增 V38 及以后版本。
- 新增表/字段遵守第 1 节约定；破坏性变更必须先备份、评审、验证。
- 软删除表（`servers`、`alert_rules`）通过 `deleted` + `delete_token` 保留历史，
  唯一键包含 `delete_token` 以支持重复登记。
- 迁移脚本与实体/Mapper 同步修改；`./mvnw test` 必须通过。

## 6. 代码映射

| 层 | 位置 |
|---|---|
| Flyway DDL | `server/src/main/resources/db/migration/` |
| Mapper XML | `server/src/main/resources/mapper/**` |
| 实体 | `server/src/main/java/cloide/server/module/*/entity/` |
| 数据访问 | `server/src/main/java/cloide/server/module/*/mapper/` |
| 表结构与契约的交叉校验 | `node scripts/check-openapi.mjs`（只校验 REST 路径，不校验表结构） |
