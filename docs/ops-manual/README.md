# Cloide 企业平台运维手册

新装企业中控从[工程 API 与节点接入](工程工作台接入.md)开始。本轮优先完成后端契约测试与节点执行验收。平台管理台由项目负责人另选框架；早期临时浏览器客户端证据存档，云 IDE 的网关与 code-server 独立接入。查某项能力已经在哪种机器上验证，请看[实施与验证记录](../logs/20261112-企业平台实施与验证.md)。

## 软件工程与企业运维

| 任务 | 对应手册 |
|---|---|
| 安装 Java 中控、Agent 与开发节点，核对候选归档 | [候选版安装与核验](发行候选版构建与核验.md) |
| 配置项目身份、仓库绑定、用户工作区与编码节点 | [企业 OIDC](企业OIDC登录.md) · [GitHub App](../ops/github-app-repositories.md) · [云 IDE](../../deploy/workspace/README.md) · [Pi 节点](../../worker/README.md) |
| 派发固定提交测试、处理未知结果、发布与回滚 | [节点作业协议](../protocol/node-jobs-v1.md) · [签名发布执行器](../../deploy/release/README.md) |
| 恢复企业业务数据库、缓存、队列或中控 | [中间件操作与恢复](../../deploy/middleware/README.md) · [中控密钥、数据库与制品恢复](../../deploy/recovery/README.md) |

## Server（Java 后端 + 依赖组件）

| 手册 | 适用场景 |
|---|---|
| [cloide-shell-Server-部署安装手册.md](./cloide-shell-Server-部署安装手册.md) | 从零安装（MySQL/RabbitMQ/JAR/Nginx）、启停、健康检查、首次验收清单 |
| [华为云DevSpace-部署手册.md](./华为云DevSpace-部署手册.md) | 华为云 DevSpace 容器（无 systemd/Docker）单机部署、反向隧道、公网入口与 MaaS 接入 |
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
- 后端联调从[OpenAPI 索引](../openapi/README.md)与[节点作业协议](../protocol/node-jobs-v1.md)进入。
- 回归范围及所需环境见[HTTP/WS 双机验收](../../scripts/api-test/README.md)与[下一轮开发计划](../plans/20261112-现状盘点与下一轮开发计划.md)。
- 仓库设置：`docs/ops-manual/repo-setup.md`。
