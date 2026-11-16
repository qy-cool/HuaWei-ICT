# api-test 回归脚本

HTTP/WebSocket 端到端回归脚本，需要**运行中的后端 + 数据库**，不是单元测试。

## 环境变量

| 变量 | 默认值 | 用途 |
|---|---|---|
| `CLOIDE_BASE_URL` | `http://127.0.0.1:18081` | 大多数脚本的默认地址；18081 是为隔离 validation 实例预留的端口 |
| `CLOIDE_DOCKER_BASE_URL` | `http://127.0.0.1:18080` | `verify-docker-compose.mjs` 使用；compose 默认把 server 发布到 18080 |
| `CLOIDE_DOCKER_BOOTSTRAP_TOKEN` | 空 | 空库首管理员初始化令牌，与 compose `.env` 的 `AUTH_BOOTSTRAP_TOKEN` 同值 |
| `CLOIDE_VALIDATION_MYSQL_PASSWORD` | 空 | 需要直连 MySQL 的脚本使用 |
| `CLOIDE_VALIDATION_DB_ADMIN_PASSWORD` | 空 | 首管理员并发编排器创建/删除隔离库的 MySQL 管理员口令 |
| `CLOIDE_VALIDATION_ADMIN_PASSWORD` | 编排器自动生成 | 首管理员并发脚本注册用的密码；手工运行 `.mjs` 时必填 |

三个端口不是笔误：普通脚本面向独立 validation 实例（18081），compose 验收脚本面向 compose 发布的 18080；首管理员并发脚本由编排器固定使用 18183。需要时用 `CLOIDE_BASE_URL` 覆盖。

## 用法

```bash
cd scripts/api-test
npm ci

# 独立 validation 实例
CLOIDE_BASE_URL=http://127.0.0.1:18081 node verify-agent-ws.mjs

# compose 全栈（含 agent profile 时才会验证 C5）
node verify-docker-compose.mjs
```

脚本清单与依赖见各文件头部注释；`verify-docker-compose.mjs` 会打印 `AGENT_READY=<server_id> <agent_token>`，供编排器拉起 `docker compose --profile agent up -d agent`。

## 节点作业双机回归

[双机验收程序](run-project-jobs-e2e.py) 会在两个测试主机的独立临时目录启动中控和开发节点。中控使用独立的回环 MySQL 实例、随机口令与测试库，开发节点使用低权限账户和离线 Git 工作区。两台机器只需要接受管理员使用的 SSH 连接。所有端口转发绑定回环，确认主机指纹后运行。

运行机需要 Python 3.11+、aiohttp、ssh 和 scp；中控需要 Java 21、MySQL 8.4 的服务端及客户端、Python 和 setpriv；节点需要与 Agent 匹配的 Linux 架构、Git、bwrap、prlimit、setpriv、ldd、od 和 Python。测试登录账号需有权创建隔离 MySQL 数据目录并切换执行用户。无需提供现有 MySQL 的凭据。

```sh
python scripts/api-test/run-project-jobs-e2e.py --central CONTROL_SSH_ALIAS --worker NODE_SSH_ALIAS \
  --jar PATH_TO_CURRENT_SERVER_JAR --agent PATH_TO_LINUX_AGENT
```

测试从空库完成初始化管理员、创建项目、登记节点、分配测试环境和幂等派发。节点在隔离目录检出指定提交，测试验证凭据隔离并生成一次随机标记。验收代理只丢弃首个结果确认并断开连接，核对重连结果、执行标记、REST 报告和日志，再清理两台机器上的夹具。测试关闭 Redis 和 RabbitMQ，覆盖数据库扫描补调路径。消息中间件、浏览器页面和多实例中控分别验收。

增加 `--failover` 会打开中控的数据库连接归属校验。代理在首次 ACK 到达后暂停转发，夹具读取数据库中的实际节点、实例和会话标识，对自己启动的第一个 Java 进程发送 SIGKILL，再用相同密钥、MySQL 和不同回环端口启动第二个 JVM。Agent 向第二个实例重放已经完成的结果，验收从数据库验证归属变更，并用原登录 JWT 在新实例查询原作业、测试标记和退出码。该测试覆盖同机跨 JVM 冷接管；使用独立控制面机器和数据库集群的网络分区演练需另行组织。

`--capture-build` 为测试仓库登记 static-tar 输出。用例核对节点声明的操作系统、架构、仓库和工具链，在节点隔离环境中检出固定提交、执行测试并打包 `index.html`。正式上传器在 SSH 回环转发上使用自己的节点凭据发送捕获的文件。代理丢弃首次成功响应，重试后核对两份回执为同一制品，并从用户 API 下载 tar 复核来源作业、提交、节点、字节数、SHA-256 和实际页面。开发节点需有 tar、Python 3.8+。可同时使用 `--failover` 和 `--releases` 核验后端跨进程切换、制品与签名发布。
## 企业工作区与制品验收

在双机作业验收命令中添加 `--releases`，可同时测试签名静态制品的测试发布、同摘要生产提升、坏版本恢复和指定回滚。运行机还需 Python cryptography。

工作区验收脚本 `run-workspace-e2e.py` 接收 `--central`、`--worker`、`--jar`、`--code-server`、`--guard` 和 `--browser-binary`。使用 code-server v4.145.2 官方 ARM64 tar 包、为运行机架构构建的 session-guard 与本机 Chromium/Edge。运行机需要 Python 3.11+、aiohttp、cryptography、Playwright 和 SSH，ARM 节点需要 Linux 非特权用户命名空间及 bubblewrap。每次测试会在两台机器创建并清理独立临时目录。
