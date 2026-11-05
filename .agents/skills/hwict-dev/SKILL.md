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

## 0. 比赛约束（本仓库存在的目的）

本仓库是第十一届华为 ICT 大赛创新赛·赛题1（基于华为云产品打造 AI 创新应用）
的团队开发仓库。所有开发与流程决策优先服从比赛硬性要求：

- **开发工具（团队决定：不作日常工具）**：日常开发使用团队自选工具。为满足
  参赛资格（参赛须知⑤：未采用赛题要求的华为技术不符合资格），**建议**在
  报名审核通过后，用码道 CodeArts 代码智能体 + Skills 完整跑通一次
  "开通 RDS/Redis → 部署上云"链路，并把过程留痕存入
  `docs/competition/codearts-trace/`（赛题要求材料体现华为云产品在需求
  理解、代码生成、构建、修复、部署各环节的使用价值；是否执行此最小合规
  动作由队长最终决定，不做则删除该留痕目录）。
- **云服务**：应用部署在华为云；华为云 AK/SK、MaaS API Key 等凭据只走
  环境变量或华为云托管配置，不硬编码、不入库。
- **交付物**：初赛材料五要素（方案背景与价值、创新性解读、数据集选取与
  处理、关键代码片段解读、可量化效果）的素材日常沉淀在
  `docs/competition/`，不要赛前突击补写。
- **开源**：入围中国总决赛的作品需开源至 Gitee/GitHub；提交信息、README、
  许可证从第一天就按可公开标准写。
- **赛程**：报名截止 2026-11-30；初赛 2026-12；中国总决赛 2027-03；
  全球总决赛 2027-05（决赛答辩 15 分钟展示 + 5 分钟问答，组委会有材料
  复现环节，演示必须真实可跑）。

## 1. 技能索引

| 目录 / 任务 | 加载的 skill |
|------|------|
| `server/`（Java/Spring Boot 后端） | `hwict-java` |
| `agent/`（Go 1.23 采集 Agent） | `hwict-go`（待建）；当前按 `go vet ./... && go test ./...` |
| `android/`（Kotlin 2.0.21 + Compose 客户端） | `hwict-android`（待建）；当前按 `./gradlew :app:testDebugUnitTest` |
| 数据库设计 / SQL / Flyway 迁移（RDS MySQL） | `hwict-mysql` |
| `web/`（React 前端） | `hwict-react` |
| shadcn/ui 组件使用 | `hwict-shadcn` |
| 页面视觉与 UX 设计决策 | `hwict-ui-design` |
| React 性能与重渲染优化 | `hwict-react-perf` |
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
| REST 契约 | 更新 `docs/openapi/` 对应 JSON + 前端类型定义对齐 |
| 前端 | `pnpm typecheck` + `pnpm lint` + `pnpm test`；交付前 `pnpm build`（`web/`，脚本以 `package.json` 为准） |
| 数据库迁移 | 只新增 Flyway 版本脚本（不改已执行脚本）+ `./mvnw test` |
| 决策笔记 | `node .agents/skills/hwict-notes/scripts/verify-agent-notes.mjs`（PR 上由 CI 自动执行） |
| 文档 / 链接 | `node .agents/skills/hwict-docs/scripts/check-docs-links.mjs`（PR 上由 CI 自动执行） |
| 比赛/项目文档 | 交叉引用的路径存在；不含真实凭据、内网 IP |
| CI 工作流（`.github/workflows/`） | YAML 缩进与触发路径自查；以最近一次 Actions 运行记录为准；代码 PR 会运行 `Verify Server` / `Verify Web` / `Verify Agent` / `Verify Android`，加入分支保护后作为合并门禁 |

## 3. 开发与联调环境

联调环境是一台非华为云的远程服务器，**目前尚未落地本项目组件**（缺口见下方
工具链实况）。它只承担「跑得起来、连得上」的职责，交付与验收仍必须在华为云
完成（见第 0 节）。该机的真实地址、账号与凭据不入库，引用时统一用
`SERVER_IP_OR_DOMAIN` 占位。

**规格**（2026-10-29 实测快照，以实际为准）：OpenCloudOS 9.6 / 2 核 /
3.6 GB 内存（可用约 2.7 GB）/ 60 GB 磁盘（可用约 33 GB）/ 1 GB swap。

**工具链实况**（只列与项目要求的差异，版本基线见 `README.md` 环境要求）：

- 已有：Java 21 运行时（**仅 JRE，无 `javac`**）、Git、MySQL 8.4（已安装未启用）。
- 缺失：Node 24、pnpm、Go 1.23、Redis、RabbitMQ。
- `./mvnw` 可用但前提是先补齐 JDK；工具链大版本须与 CI 一致（见 `hwict-tests` 第 1 节）。

**端口**：

- 项目占用的固定端口：`18080`（Java）、`3306`（MySQL）、`5672`（RabbitMQ）、
  `6379`（Redis）、`5173`（Vite）。以上在 2026-10-29 实测空闲。
- 该机另有 nginx、frps 等服务在跑，且 frps 的转发端口随客户端连接动态增删。
  **启动服务前用 `ss -tln` 实测核对，不要假设端口空闲**；占用清单不入库，
  以免写死成过时信息。
- `18080` 与 `18081` 只差一位，配置时勿写混。

**使用边界**：

- 该机同时承载其他站点，并有一个停用的 MySQL 实例及其既有业务数据：
  重启 nginx、改写站点配置、启用 MySQL 前先确认影响面，
  **不要当作可随意重置的沙箱**。
- OS 与 `deploy/server/README.md` 的目标平台相同，但那份手册面向交付部署，
  **不要把本机当作部署目标机**。
- 可以做：单次 `./mvnw test`、单次 `pnpm build`、后端 + 数据库常驻联调。
- 不要做：后端常驻的同时跑 Maven 或 Vite 构建（峰值可达约 2.9 GB，接近或
  超出可用内存，会吃 swap）；Android `assembleDebug`（改在本地执行）；
  并发压测。
- 该机不常驻 Docker：容器化依赖会额外占用 0.2–0.5 GB，需要时再启用，并相应
  减少可并跑的服务。

## 4. 通用原则

- **复用成熟依赖优先**：写工具类能力（加解密、重试、限流、HTTP 封装等）
  前，先确认 Spring / shadcn / 已有 utils / 华为云 SDK 是否已提供；升级或
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
3. 实现 + 按第 2 节跑聚焦验证（CodeArts 合规留痕见第 0 节）。
4. 涉及架构 / 契约 / 安全决策变更 → 更新或新建决策笔记。
5. 交付前跑受影响范围完整验证 + `hwict-review`（如需自审）。
6. 按 `hwict-git` 规范提交，在 `docs/logs/` 记日志。
