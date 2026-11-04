# 开发日志：cloide-shell 迁移

**日期**：2026-10-29
**操作人**：ZCode（AI 协作）
**关联计划**：`docs/plans/20261029-cloide-shell-迁移.md`
**关联决策**：`.agents/notes/implemented/architecture/2026-10-29-cloide-shell-migration.md`

## 改动摘要

把作者原有的 cloide-shell 监控平台迁入本仓库：

- 保留 `server/`（Java 21 / Spring Boot 3.4.13）、`agent/`（Go 1.23）、
  `android/`（Kotlin 2.0.21 + Compose）三端。
- 废弃 Vue Web 前端；只摘出 `scripts/check-openapi.mjs` 作为契约门禁。
- 品牌统一：Java 包 `cloide.server`、Android 包 `cloide.app`、配置前缀 `cloide`、
  数据库 `cloide`、Go module `cloide/agent`、systemd 服务 `cloide-server` / `cloide-agent`。
- 契约迁入 `docs/openapi/`（7 份，OpenAPI 3.0.3）与 `docs/protocol/`（7 md + 1 schema）。
- 运维手册迁入 `docs/ops-manual/` 并删掉 Vue 构建、前端验收、宝塔假设。
- 新写 `docs/tech-summary/architecture.md`、`database-design.md`。
- 部署资产归位 `deploy/server/`、`deploy/agent/`；compose 删 web 服务，API 发布 18080。
- 根 `.gitignore` / `.gitattributes` / `.editorconfig` 只合并规则，不整文件覆盖；
  新增 `.dockerignore`、`.env.example`。
- 新增 `Verify Agent`、`Verify Android` 工作流；`hwict-dev` / `hwict-git` /
  `hwict-java` / `hwict-docs` / `review-policy` 同步新端、新包名、版本例外。

## 验证结果

| 项 | 命令 | 结果 |
|---|---|---|
| 后端单测 | `cd server && ./mvnw -B -ntp test` | 875 tests，0 failures，BUILD SUCCESS |
| Agent | `cd agent && go vet ./... && go test ./...` | 全部通过 |
| Agent 构建 | `go build ./cmd/cloide-agent` | 成功（12MB） |
| Android 单测 | `./gradlew :app:testDebugUnitTest` | 128 tests 通过 |
| Android 构建 | `./gradlew :app:assembleDebug` | 成功（19MB APK） |
| OpenAPI 契约 | `node scripts/check-openapi.mjs` | 7/7 通过 |
| 文档链接 | `node .agents/skills/hwict-docs/scripts/check-docs-links.mjs` | 62 篇通过 |
| 决策笔记 | `node .agents/skills/hwict-notes/scripts/verify-agent-notes.mjs` | 3 篇通过 |
| Compose 结构 | `docker compose --env-file .env.example config` | 通过（含 agent profile） |
| Compose 起停 | `docker compose up -d mysql rabbitmq` | **未执行**：本机 Docker 守护进程未启动 |

## Review 修复（2026-10-30）

提交前自审发现并修复：

- **P1 CORS 空白名单启动失败**：`AppProperties.Cors.allowedOrigins` 去掉
  `@NotEmpty`，默认空列表；`CorsConfig` 在空列表时不启用凭据；测试配置改用空
  CORS；`CorsConfigTests` 改为覆盖空白名单合法、空配置禁用凭据、显式 Origin 启用
  凭据。定向 `CloideShellApplicationTests -DargLine="-Dcloide.cors.allowed-origins="`
  通过（修复前该用例 `APPLICATION FAILED TO START`）。
- **P2 协议文档旧环境变量**：协议文档中的旧前缀统一改为 `CLOIDE_*`；
  命令域 kill switch 更正为 `AI_COMMAND_ENABLED`。
- **P2 Agent 示例默认开终端**：`deploy/agent/cloide-agent.env.example` 的
  `CLOIDE_TERMINAL_ENABLED` 改为 `false`，需要时显式开启。
- **P3 清理**：协议文档残留旧开发日志引用、`ConsumerQueueNames` 注释、
  ProGuard 过期注释、`install.sh` JAR 默认路径、`scripts/api-test` 端口说明。
- 复跑：后端 875 tests、Agent `go vet/test`、Android 128 tests、OpenAPI 7/7、
  文档 62 篇、笔记 3 篇全部通过。

## 第四轮 Review 修复（2026-11-04）

- **P2 api-test 旧 jar 名**：5 个编排/验证脚本与《升级与回滚手册》中的
  `server-0.0.1-SNAPSHOT.jar` 全部改为 `cloide-server-0.0.1-SNAPSHOT.jar`。
- **P3 首管理员并发脚本**：移除硬编码口令，改由 `CLOIDE_VALIDATION_ADMIN_PASSWORD`
  注入；`.sh`/`.ps1` 编排器在未提供时生成一次性随机口令；README 补充该变量与
  18183 端口说明。
- **P3 备份/恢复密码传递**：`backup.sh` / `restore.sh` 改用 `MYSQL_PWD`，
  避免密码出现在进程列表。
- **P3 数据库 SSL**：JDBC `useSSL` 参数化为 `DB_USE_SSL`（默认 false；
  华为云 RDS 建议 true），并同步 `.env.example`、compose 与服务端 env 模板。
- **P3 Agent systemd 加固**：增加 `NoNewPrivileges` / `ProtectSystem` /
  `ProtectHome` / `PrivateTmp` / `ReadWritePaths`；仍以 root 运行以支持
  PTY/命令域。
- 复跑：后端 875 tests（JDK 21；本机默认 JAVA_HOME 为 JDK 25 时 Mockito 不兼容）、Agent、OpenAPI 7/7、文档 62 篇、笔记 3 篇；
  Android 未改动，沿用 128 tests 结果。

## 未执行 / 后续

- **容器起停与 `/api/health`、`/api/ready`（以及 local/dev 的 `/api-docs`）实机验证**未执行，
  原因是 Docker Desktop 守护进程未运行；启动 Docker 后按
  `docs/ops-manual/cloide-shell-Server-部署安装手册.md` §6.5 执行。
- Boot 4.1.2 / MyBatis-Plus 3.5.18 / springdoc 3.2.0 / OpenAPI 3.1.0 升级另开 PR。
- 华为云 RDS/Redis/MaaS/OBS 适配后续独立决策。
- Android release 签名、keystore 与 CI 注入后续补齐。
- 旧 Vue 前端不迁入；`web/` 后续按 `hwict-react` 重建。

## 涉及目录

`server/`、`agent/`、`android/`、`deploy/`、`scripts/`、`docs/openapi/`、
`docs/protocol/`、`docs/ops-manual/`、`docs/tech-summary/`、`.agents/skills/`、
`.github/workflows/`、根配置文件。
