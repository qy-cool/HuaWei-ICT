# 上游组件、实际用途与发行材料

2026-11-12 按工作区、候选包和验收记录核对。主仓自研部分沿用 MIT。开发者本地的 `harness/` 和 `ide/` 保存上游参考副本，已从主仓检出中排除。下表使用安装版本标记可执行依赖，用完整提交号标记阅读过的源码。本轮维护后端、节点及独立工作区网关的依赖。平台管理台框架留给项目负责人选定，既有临时客户端和历史归档仅作为核验记录。候选包内容以相应版本的组包脚本和具体归档为准。[主仓许可证](../../LICENSE) · [组包输入](../../scripts/release/build-kit.py)

## 正在运行的组件

| 组件与固定来源 | 平台怎样使用、怎样安装 | 目前验证到哪里 | 发行材料 |
|---|---|---|---|
| Pi Coding Agent，npm 包 `@earendil-works/pi-coding-agent@1.4.0`。本地参考提交 `e65b3b88c08412bb8ffbd21fb5dc5756064a6e5a` | Node.js 24+ Worker 创建独立 Pi 会话、装配项目允许的文件工具并回传事件。候选包携带 Worker 源码、清单和 npm 锁文件，节点按锁文件安装。 | 真实 Pi SDK、Git、Java 中控和 MySQL 使用回环模型服务，完成审批、落盘、差异，并保留人工修改。组件测试另覆盖恢复、取消和只读计划。企业真实模型的运行质量待现场验收。 | 已装 npm 包的元数据及参考源树标明 MIT。打包 Worker 依赖副本时应随实际 npm tarball 及传递依赖保留其许可、版权文本，按锁文件核对完整性。[依赖锁](../../worker/package-lock.json) · [验收记录](../logs/20261112-编码任务端到端验收.md) |
| code-server，官方 Linux 归档 `v4.145.2`。amd64 SHA-256 为 `2f6db6c3e8b733dd6b3aeee83532e148b2bae2e392353ba85452d03572348962`，arm64 为 `e867a34416e2da4a83496b09580001893e7a4e5bb58a0624c4fcd22819e7d53d` | 开发节点单独安装上游归档。主仓发行候选包含会话代理与工作区进程管理器。 | ARM64 实际校验归档，在 Edge 中编辑、执行终端测试、休眠和恢复，撤权后既有连接关闭。amd64 在此列出上游归档摘要，真实编辑器端到端记录覆盖 ARM64。 | code-server 项目标示 MIT。若重新分发编辑器归档或预装扩展，应逐一保留随附通知，并核对 VS Code 派生部分、二进制依赖和具体扩展的条款。[固定发行](https://github.com/coder/code-server/releases/tag/v4.145.2) · [双机记录](../logs/20261112-浏览器云IDE双机验收.md) |
| IntelliJ Java PSI，实际 Maven 依赖 `org.jetbrains.kotlin:kotlin-compiler:2.0.21`。IntelliJ Community 本地参考提交 `74dbd42b7a8787b0707437cb857c68e78199f611` | 可选 Java 分析器每次查询启动独立 JVM，返回类、方法、文件、行号及 SHA-256。Pi 只送入一个已授权文件。候选包携带适配器源码和 POM，管理员按 Maven 坐标安装依赖。 | 已实测多个源码根中的订单方法查找及 Pi 单文件模式。完整 standalone-analysis 聚合物、复杂多模块的外部符号解析、索引内存与冷启动需单独验证。 | 以 Maven 实际 JAR、POM、传递依赖及随包声明核对。IntelliJ 参考树另含 `LICENSE.txt`（JetBrains Open-Source Build Terms 1.3）、`NOTICE.txt` 和 `license/` 材料；采用完整 IDE 构建时须按该构建核对。[分析器](../../worker/java-analysis/README.md) · [固定依赖](../../worker/java-analysis/pom.xml) |

## 设计参考与候选

| 本地源码快照 | 当前用途 | 如要引入发行物需补什么 |
|---|---|---|
| ZCode，`3c5b227649371507ddc4eb46ac5133afe6db1cab` | 借鉴三档自主程度与独立计划开关。编码任务 REST 接收执行模式，Java 中控和 Pi Worker 逐次传递，缺省采用变更前确认。现有发行物没有 ZCode CLI 或 Web 程序。 | 参考树根 LICENSE 为 Apache-2.0，另含 `NOTICE.md`、`THIRD-PARTY-NOTICES.md` 和第三方清单。若使用具体源码、资产或原生工具，应对照选中的构建和平台归集材料。[ZCode 上游](https://github.com/zai-org/ZCode) · [当前 Worker](../../worker/README.md) |
| OpenCode，`5c0376f949dd9cfd5234c6ff69262e9e23464763` | 第二编码引擎候选，当前工程任务使用 Pi。 | 本地根 LICENSE 为 MIT。接入前验证相同的授权、取消、事件恢复和工作树隔离，并清点实际发行依赖。[OpenCode 上游](https://github.com/anomalyco/opencode) |
| DeepSeek Harness，`f3803c96974ad4a94195fb97e5c637e9c2a3afe3` | 编码运行时阅读样本，主仓尚无相应引擎适配和验收。 | 本地根 LICENSE 为 MIT。采用时登记官方制品来源、固定版本、许可文本与运行证据。 |
| ZCode Plugins，`d5690038b1e36293ee684a49de12ae41084e25e8` | 插件机制参考，当前 Pi 任务采用显式装配的项目工具。 | 本地根 LICENSE 为 Apache-2.0。每个拟启用插件需明确其来源、版本、请求能力和独立依赖的许可。 |

## 维护者如何核对候选包

1. 检查同一源码版本的 amd64 与 arm64 归档，逐项核对实际 JAR 内的库、Go 链入模块及两份包内的 `licenses/audit.json`。历史候选归档中还保留了临时客户端的第三方许可文件，后端交付依据当前包中实际包含的组件计算。组包工具将未提取到嵌入声明的依赖记入 `withoutEmbeddedNotice`。2026-11-12 的两个本机候选包各有 45 条待补核，首轮含临时客户端的 CycloneDX 盘点记录 584 个组件，本次仅后端的最终候选归档清点 574 个组件。维护者应按确切制品逐项从 POM、官方发布包或固定版本源码补齐可追溯的文本、版权或 NOTICE。[构建与验证记录](../logs/20261112-企业平台实施与验证.md) · [组包程序](../../scripts/release/build-kit.py)
2. 另行安装的 Pi 依赖、code-server、Java 分析器、业务镜像与插件按安装方实际选择维护清单。更新任何一项时，同时记录下载来源、版本、摘要、适用 CPU 架构和变更后的回归结果。采用补丁时记录补丁用途和对应的上游提交。
3. 在配置好的 GitHub 发行仓库生成并校验归档校验和、SBOM 与构建来源证明，复核待补条目后发布。仓库已有草稿发行工作流，本地核对留下的是候选归档记录。[工作流](../../.github/workflows/release-candidate.yml) · [发行手册](../ops-manual/发行候选版构建与核验.md)
