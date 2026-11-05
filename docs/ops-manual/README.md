# cloide-shell 运维手册索引

运维与使用手册总目录。按主题选择：

## Server（Java 后端 + 依赖组件）

| 手册 | 适用场景 |
|---|---|
| [cloide-shell-Server-部署安装手册.md](./cloide-shell-Server-部署安装手册.md) | 从零安装（MySQL/RabbitMQ/JAR/Nginx）、启停、健康检查、首次验收清单 |
| [cloide-shell-升级与回滚手册.md](./cloide-shell-升级与回滚手册.md) | 版本升级流程、回滚决策矩阵（JAR 可回滚 / 数据库不可回滚） |
| [cloide-shell-备份与恢复手册.md](./cloide-shell-备份与恢复手册.md) | 数据库 + 密钥备份、恢复、演练 |
| [cloide-shell-安全检查手册.md](./cloide-shell-安全检查手册.md) | 端口/数据库/RabbitMQ/密钥/TLS 安全基线 + 上线前检查表 |
| [cloide-shell-RabbitMQ-运维手册.md](./cloide-shell-RabbitMQ-运维手册.md) | Outbox/告警消费链路、vhost/拓扑、DLQ、积压、停机恢复 |

## Agent（Go Agent）

| 手册 | 适用场景 |
|---|---|
| [Go-Agent 部署使用手册.md](./Go-Agent%20部署使用手册.md) | Agent 构建（Linux 交叉编译）、Token 发放、systemd 部署、验证、内网部署 |
| [Go-Agent-WSS公网验收手册.md](./Go-Agent-WSS公网验收手册.md) | 公网 WSS/HTTPS 部署验收清单（域名/TLS/证书/反代与端到端验证步骤） |

## 配套资产与文档

- 部署资产：`deploy/server/`（install.sh、backup.sh、restore.sh、systemd unit、nginx 模板）、`deploy/agent/`（install-agent.sh、systemd unit、logrotate）。
- 接口契约：`docs/openapi/`；WebSocket/RabbitMQ 协议：`docs/protocol/`。
- 回归脚本：`scripts/api-test/`（HTTP/WS 端到端与断链恢复）。
- 仓库设置：`docs/ops-manual/repo-setup.md`。
