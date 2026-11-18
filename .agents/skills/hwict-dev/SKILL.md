---
name: hwict-dev
description: HuaWei-ICT 开发总纲。当在本仓库进行任何实现、重构、调试、测试、构建或文档工作时自动加载。触发场景：开始开发任务、选择应加载的规范 skill、确定改动后的最小验证命令。
when_to_use: 开始任何开发任务 / 选择验证命令 时
user-invocable: true
---

# HuaWei-ICT 开发总纲

本 Skill 是其余规范的总入口：先在这里确定"改哪里 → 加载哪个规范 → 跑哪些
验证"，再动手。

> 结构改编自团队既有项目（Lithe-IDEA / cloide-shell）的同名 skill，按本仓库适配。

## 0. 产品和部署目标

项目面向企业独立自托管，开发路线和阶段验收见[项目总体规划](../../../docs/plans/20261112-企业自托管AI软件工程平台总体规划.md)。代码、发布物、依赖来源和运维示例须可公开复现。企业指定模型端点和目标节点，凭据通过受管配置注入。历史竞赛资料保存在档案目录。

本阶段优先补齐后端文档、测试与发行边界。管理前端框架由用户另行选择，后端按 OpenAPI、WebSocket 与工程作业协议交付。

## 1. 技能索引

| 目录 / 任务 | 加载的 skill |
|------|------|
| `server/`（Java/Spring Boot 后端） | `hwict-java` |
| `agent/`（Go 1.23 采集 Agent） | `hwict-go`（待建）；当前按 `go vet ./... && go test ./...` |
| `android/`（Kotlin 2.0.21 + Compose 客户端） | `hwict-android`（待建）；当前按 `./gradlew :app:testDebugUnitTest` |
| 数据库设计 / SQL / Flyway 迁移（RDS MySQL） | `hwict-mysql` |
| 后续明确选用 React 或 shadcn/ui 并授权相应前端开发时 | 按选择加载 `hwict-react`、`hwict-shadcn`、`hwict-ui-design` 或 `hwict-react-perf` |
| Git 提交 / 分支 / 合并 | `hwict-git` |
| 文档 / OpenAPI / 协议文档 / 比赛材料 | `hwict-docs` |
| 测试代码 / 测试基础设施 | `hwict-tests` |
| 架构决策笔记 | `hwict-notes` |
| 提 PR 前自审 | `hwict-review` |

改代码前先看 `.agents/notes/implemented/` 是否有相关决策（按关键词检索，
不要全量读取）。

## 2. 验证矩阵

改动交付前至少跑对应行的最小验证；本机跑不了的要如实说明，不得声称通过
（联调环境能跑什么见第 3 节）。
工程骨架建立后如有出入，以实际脚本为准并回来更新本表：

| 改动 | 最小验证 |
|------|------|
| Java 后端 | `./mvnw test`（`server/`） |
| Go Agent | `go vet ./...` + `go test ./...`（`agent/`） |
| Android | `./gradlew :app:testDebugUnitTest`（`android/`）；交付前 `assembleDebug` |
| REST 契约 | 更新 `docs/openapi/` 对应 JSON，运行 `node scripts/check-openapi.mjs` 并用后端接口测试核对字段、授权和状态码 |
| 数据库迁移 | 只新增 Flyway 版本脚本（不改已执行脚本）+ `./mvnw test` |
| 决策笔记 | `node .agents/skills/hwict-notes/scripts/verify-agent-notes.mjs`（PR 上由 CI 自动执行） |
| 文档 / 链接 | `node .agents/skills/hwict-docs/scripts/check-docs-links.mjs`（PR 上由 CI 自动执行） |
| 比赛/项目文档 | 交叉引用的路径存在；不含真实凭据、内网 IP |
| CI 工作流（`.github/workflows/`） | YAML 缩进与触发路径自查；以最近一次 Actions 运行记录为准；代码 PR 会运行 对应后端、Agent 及已有 Android 客户端的适用检查，加入分支保护后作为合并门禁 |

## 3. 开发与联调环境

联调使用云中控和主动连接的内网 Linux 节点。开始前核验资源、端口、已有服务及持久卷。所有测试使用独立目录、进程、端口、数据库和凭据；结束后清理并检查残留。真实账号、别名和地址保留在本地。双机环境覆盖登记、受控测试与可恢复回执，多故障域验收需要独立机器。

## 4. 通用原则

- **复用成熟依赖优先**：写工具类能力（加解密、重试、限流、HTTP 封装等）
  前，先确认 Spring / 已有 utils / 华为云 SDK 是否已提供；升级或
  启用既有能力能解决的不写并行实现，禁止因"跨端"或"接入麻烦"建立平行
  实现。确需自研，把证据、被否的复用选项和边界记入决策笔记。遵守上游
  许可，不逆向或复制专有工具。
- **显式失败处理**：不吞异常、不用异常做正常流程控制；错误要么处理、要么
  带上下文上抛；用户可见失败信息可执行、不泄露敏感值。
- **不硬编码环境细节**：凭据、密钥、机器路径、内网 IP 不入代码与测试
  fixture；示例地址统一用 `SERVER_IP_OR_DOMAIN`，云上地址走配置。
- **诚实汇报**：未运行的验证不声称通过；本机跑不了的检查明确列出；验证
  结论与实际执行范围一致。
- **改动保持可审查**：一个提交一件事；不做无关格式化与顺手重构；生成的
  构建产物不入库。

## 5. 工作流

1. 领任务 → 在 `docs/plans/` 建计划（见 `hwict-docs`）。
2. 按第 1 节加载规范 skill，检索相关决策笔记。
3. 实现 + 按第 2 节跑聚焦验证。
4. 涉及架构 / 契约 / 安全决策变更 → 更新或新建决策笔记。
5. 交付前跑受影响范围完整验证 + `hwict-review`（如需自审）。
6. 按 `hwict-git` 规范提交，在 `docs/logs/` 记日志。
