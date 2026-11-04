# 开发计划：cloide-shell 迁移

**日期**：2026-10-29
**状态**：已完成

## 目标

把作者原有的 cloide-shell 监控平台迁入本仓库，保留 Java 后端、Go Agent、
Kotlin 安卓三端，废弃 Vue Web 前端；统一品牌、包名、配置前缀与部署路径。
本轮**不做任何版本升级**，旧项目原版本原样迁入，差异登记为迁移期例外。

## 任务清单

- [x] 批次 0：根配置合并、规范层同步、决策笔记、CI 工作流
- [x] 批次 1：`server/` 迁入与命名改造，`./mvnw test` 跑绿
- [x] 批次 2：`docs/openapi`、`docs/protocol`、`scripts/check-openapi.mjs`
- [x] 批次 3：`agent/` 迁入与改造，`go test ./...`
- [x] 批次 4：`android/` 迁入与改造，`gradlew :app:testDebugUnitTest`
- [x] 批次 5：compose、`.env.example`、`deploy/`、`scripts/api-test`
- [x] 批次 6：README、2 份设计文档、8 份运维手册
- [x] 批次 7：全仓脱敏、链接校验、清理源目录与残留进程

## 验证结果（2026-10-29）

| 项 | 结果 |
|---|---|
| `server/` | `./mvnw -B -ntp test`：875 tests，0 failures，BUILD SUCCESS |
| `agent/` | `go vet ./...`、`go test ./...`、`go build ./cmd/cloide-agent` 全部通过 |
| `android/` | `:app:testDebugUnitTest`：128 tests 通过；`assembleDebug` 产出 19MB debug APK |
| OpenAPI | `node scripts/check-openapi.mjs`：7/7 契约与 Controller 路径一致 |
| 文档 | `check-docs-links.mjs`：62 篇文档引用均存在 |
| 决策笔记 | `verify-agent-notes.mjs`：3 active notes 通过 |
| Docker Compose | `docker compose --env-file .env.example config` 通过；**容器起停未执行**（本机 Docker 守护进程未启动） |

2026-10-30 review 修复：CORS 空白名单、协议环境变量名、Agent 终端默认值；
复跑后端 875 / Android 128 / Agent / OpenAPI 7-7 / 文档 62 / 笔记 3 全部通过。

## 迁移边界

**搬入**：`旧后端目录/` → `server/`；`旧 Agent 目录/` → `agent/`；
`旧安卓目录/` → `android/`；`旧契约目录/` → `docs/openapi/`；
`旧协议目录/` → `docs/protocol/`；`旧运维手册目录/` → `docs/ops-manual/`；
`api-test/` → `scripts/api-test/`；`docker-compose.yml` 与部署资产按端归位。

**不搬**：`旧 Vue 前端/`（只摘 `check-openapi.mjs`）、`项目需求与规范.md`、
`旧文档目录` 下 264 篇历史文档、旧 `README.md`、构建产物、真实密钥、旧 `.git`。

## 版本口径

| 层 | 本轮沿用 | 仓库目标基线 |
|---|---|---|
| 后端 | Spring Boot 3.4.13；MyBatis-Plus 3.5.12；springdoc 2.8.9；Spring AI 1.0.0 | Spring Boot 4.1.2；MyBatis-Plus 3.5.18；springdoc 3.2.0 |
| 接口契约 | OpenAPI 3.0.3 | OpenAPI 3.1.0 |
| 数据库 | MySQL 8.4（本地） | RDS MySQL 8.0 |
| 队列/缓存 | RabbitMQ 4.3、Redis 7 | 只锁产品名 |
| Go | Go 1.23 | 未约定 |
| 安卓 | Kotlin 2.0.21 / AGP 8.13.0 / Gradle 8.13 / compileSdk 36 | 未约定 |

## 预期时间

3–5 个工作日。批次 1、3、4 的编译与单测是主要不确定性；安卓 Gradle 首次
依赖拉取最慢。

## 依赖关系

- 依赖旧仓库 `cloide-shell/` 工作区完整且 `git status` 干净。
- 批次 2 依赖批次 1 的 `server/src/main/java` 路径。
- 批次 5 的后端起停验证依赖批次 1 完成。
- 批次 7 的源目录删除依赖全部验证通过，且旧仓库 origin 可恢复。

## 风险

见 `.agents/notes/implemented/architecture/2026-10-29-cloide-shell-migration.md`
的「后果」节。主要例外：Boot 3.4.13 / OpenAPI 3.0.3 / MySQL 8.4 与仓库目标
基线不一致；Flyway 历史脚本 COMMENT 缺项；RabbitMQ 为新增中间件。
