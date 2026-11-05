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
