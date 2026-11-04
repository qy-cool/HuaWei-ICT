# Agent 笔记：cloide-shell 迁入与技术基线

状态：已实现

## 先说结论

把作者原有的 cloide-shell监控平台迁入本仓库，保留 Java 后端、
Go Agent、Kotlin 安卓三端，废弃 Vue Web 前端；品牌与标识统一为 `cloide-shell`。
迁移期**不升级任何版本**：后端保持 Spring Boot 3.4.13 / MyBatis-Plus 3.5.12 /
springdoc 2.8.9 / OpenAPI 3.0.3，本地 MySQL 8.4、RabbitMQ 4.3、Redis 7、Go 1.23、
Kotlin 2.0.21 全部沿用旧项目。仓库目标基线（Boot 4.1.2 等）保持不变，升级另议。

## 问题

- 本仓库已有 `AGENTS.md`、`.agents/skills/` 与 `docs/` 规范，但没有任何可运行
  的业务代码；比赛要求在华为云上跑通真实应用，需要一套可复用的工程底座。
- 旧项目 cloide-shell 已具备认证/RBAC、服务器管理、指标采集、告警、SSH 终端、
  AI 诊断与命令域、Go Agent、安卓客户端，功能完整，但目录、包名、文档和部署
  资产都绑定旧品牌与旧环境（旧域名、旧公网 IP）。
- 旧项目与仓库目标基线存在版本差：Boot 3.4.13 vs 4.1.2、MyBatis-Plus 3.5.12 vs
  3.5.18、springdoc 2.8.9 vs 3.2.0、OpenAPI 3.0.3 vs 3.1.0。同步升级会把"迁移"
  和"升级"两件事混在一个变更里，回滚成本高。
- 旧仓库历史文档 264 篇（开发日志、计划、缺陷档案、面试材料、部署交接）绑定
  旧前端与旧环境，搬入只会制造第二套口径。

## 决策

### 正确做法

- **目录**：`server/`（Java）、`agent/`（Go）、`android/`（Kotlin）、`deploy/`
  （systemd/nginx/备份）、`scripts/`（api-test、契约校验）；`web/` 本轮留空。
- **命名**：产品名 `cloide-shell`；Java 主包 `cloide.server`、Android 包
  `cloide.app`、Go module `cloide/agent`、配置前缀 `cloide.*`、数据库 `cloide`、
  systemd 服务 `cloide-server` / `cloide-agent`。
- **版本**：本轮全部沿用旧项目版本，不做升级；`server/pom.xml` 是后端版本的
  唯一事实源，`docs/openapi/README.md` 标注契约当前为 3.0.3。
- **文档**：只迁 `OpenApi-SuMon`、`Protocol-SuMon`、`Use-manual`；在
  `docs/tech-summary/` 下新写架构与数据库设计文档；历史日志、计划、
  缺陷档案、面试材料、部署交接不迁。旧 `项目需求与规范.md` 不搬、不引用。
- **git**：不复制旧 `.git`，顶层 `HuaWei-ICT` 是唯一仓库；旧 `.gitignore` /
  `.gitattributes` / `.editorconfig` 只合并缺失规则，不整文件覆盖。
- **规范层**：`hwict-dev` 登记 `agent/`、`android/` 与验证命令；`hwict-git`
  增加 `agent`、`android` scope；`hwict-java` 主包改 `cloide.server` 并登记
  迁移期版本例外；`hwict-docs` 登记 OpenAPI 3.0.3 例外。

### 不要这样做

- 不要在迁移同一次变更里升级 Boot 4 / MyBatis-Plus 3.5.18 / springdoc 3.2.0。
- 不要手工把 7 份 OpenAPI 3.0.3 改成 3.1.0；应由 springdoc 重新生成。
- 不要把 Vue 前端、264 篇历史文档或旧 `.git` 一并带进来。
- 不要整文件覆盖根 `.gitignore`；旧规则会忽略 `.agents/`，会破坏本仓库规范层。

## 考虑过的备选方案

### 备选方案一：迁入即升级到 Boot 4.1.2

一次对齐仓库目标基线。但需要同时处理 98 个文件的 Jackson 2→3 引用、
MyBatis-Plus boot4 starter、springdoc 3.2.0、Spring AI 1.0.0 的 Boot 4 兼容性，
并把 7 份契约重生成 3.1.0；迁移与升级耦合，失败时难以判断是迁移问题还是升级
问题。本轮不采用。

### 备选方案二：只保留通用底座，业务模块重写

只搬认证/JWT/RBAC/WebSocket/可靠投递，服务器监控、告警、SSH、AI 命令域全部
重写。迁移量最小，但等于重做产品，浪费旧项目已验证的链路，也错过比赛时间窗。
本轮不采用。

### 备选方案三：保留旧仓库 git 历史

用 `git filter-repo` 去掉前端与历史文档后保留 530 条提交。能保留追溯性，但会把
个人邮箱、旧 IP/域名和旧品牌带进公开仓库；旧仓库 origin 仍在，未来需要时可取回。
本轮不采用。

## 验证

- `server/` 下 `./mvnw -B -ntp test` 通过（875 tests，0 failures）；空白名单 CORS
  的完整上下文测试通过。
- `agent/` 下 `go vet ./...`、`go test ./...` 通过，`go build ./cmd/cloide-agent` 成功。
- `android/` 下 `./gradlew :app:testDebugUnitTest` 通过，`assembleDebug` 产出 APK。
- `docs/openapi/` 的 7 份契约与 Controller 路径/方法一致（`scripts/check-openapi.mjs`）。
- 全仓无旧域名、旧公网 IP、旧个人路径、旧品牌与旧前端路径残留。
- 容器起停与 `/api/health`、`/api/ready`、`/v3/api-docs` **未执行**（Docker 守护未启动）。
- `Verify Server` / `Verify Agent` / `Verify Android` / `Verify Docs Links` /
  `Verify Agent Notes` 的本地等价命令通过；GitHub Actions 待 PR 运行确认。
- 2026-10-30 review 修复：CORS 空白名单、协议环境变量名、Agent 终端默认值；
  复跑 875 tests 通过。

## 后果

- **版本例外**：迁移期与仓库目标基线不一致，后续升级需要单独 PR；期间不扩展
  新业务接口，避免把例外固化成长期基线。
- **Jackson 双栈**：`jjwt-jackson` 0.12.6 与 Spring AI 1.0.0 仍依赖 Jackson 2；
  将来升级 Boot 4 时需要决定换实现或接受 Jackson 2/3 并存。
- **OpenAPI 3.0.3**：`nullable` 写法与仓库 3.1 规范不一致；在升级前以
  `docs/openapi/README.md` 标注为准，评审时按迁移期例外处理。
- **MySQL 8.4 本地 / RDS 8.0 云上**：部署到 RDS 前需在 8.0 复跑 Flyway 与初始化
  脚本，不兼容时再决定是否本地降 8.0。
- **RabbitMQ 4.3** 是仓库基线之外的新增中间件：默认开启，`OUTBOX_ENABLED=false`
  可降级；华为云侧映射（DMS 或 ECS 自建）后续单独决策。
- **Flyway V1–V37** 部分字段缺 COMMENT，不满足 `hwict-mysql` 现行规范；已执行
  脚本不改，例外只对新脚本生效。

## 适用范围

适用于 `server/`、`agent/`、`android/`、`deploy/`、`scripts/` 以及
`docs/openapi/`、`docs/protocol/`、`docs/ops-manual/`、`docs/tech-summary/`。
不适用于 `web/`（本轮未建立）与华为云资源适配（后续独立决策）。
