# Agent 笔记：编码引擎与浏览器工作区的上游选择

状态：提议中

## 先说结论

当前后端接入 Pi 编码 SDK，并提供独立 code-server 工作区的接入契约，参考 ZCode 的执行权限交互。Java 符号查询使用已发布的 IntelliJ Java 程序结构接口（PSI）。完整独立分析组件、第二编码引擎及公开发行许可仍分别验收。

## 问题

本地 `harness/`、`ide/` 是阅读用副本，干净检出不含这些目录。即使两个引擎均可编辑文件，它们在任务中止、恢复、插件授权和上游许可方面也可能不同。

## 提案

保持中控签发项目与任务范围，由适配器显式装入允许的工具。运行时依赖由官方发布物、锁文件和摘要重建。引入另一编码引擎前，复用同一套审批、取消、会话恢复、差异校验和进程回收用例。每个实际分发组件单独登记许可与署名。

## 考虑过的备选方案

打包本机全部上游源码会让发行依赖不可重现，也会把未采用的插件带入许可核查。把整套桌面 IDE 移到服务器，需要维护完整索引、界面及扩展运行环境，当前按独立工作区和专用分析进程分别评估。

## 验收标准

已用真实 Pi SDK 1.4.0 与回环模型完成一次需审批的编辑，人工未提交改动得以保留。此前使用 code-server 4.145.2 验证过浏览器编辑与集成终端；该结果供后续客户端接入参考。使用 Kotlin 编译器 2.0.21 的 IntelliJ PSI 已能查询订单方法、文件位置和内容摘要。[编码实测](../../../../docs/logs/20261112-编码任务端到端验收.md) · [工作区实测](../../../../docs/logs/20261112-浏览器云IDE双机验收.md) · [实际依赖登记](../../../../docs/tech-summary/upstream-components.md)

后续验收要求企业真实模型完成任务、完整 standalone-analysis 的多模块外部依赖解析和资源测量、替代引擎在相同权限下通过回归。公开发行前逐项核对实际随包依赖、插件及 NOTICE。ZCode 在本次范围提供交互参考，OpenCode、DeepSeek Harness 与 ZCode Plugins 仍列候选。

## 风险

上游模型或插件若在恢复会话时自行放宽权限，中控审批就无法保证动作仍处于原授权范围。Java 分析器版本与用户工程语言级别不一致时，编译结果和真实测试仍是工程验收依据。

## 验证

在 `worker/` 中安装锁定依赖，准备 Java 21、中控 JAR 和可启动独立实例的 MySQL。复现编码会话、审批及差异。工作区与 Java 查询分别按其专用手册验证。[分析器复现](../../../../worker/java-analysis/README.md)

```sh
npm ci --ignore-scripts
node acceptance/control.mjs /absolute/path/to/server.jar /absolute/path/to/mysqld /absolute/path/to/java
```

## 适用范围

`worker/`、`deploy/workspace/` 和[第三方组件登记](../../../../docs/tech-summary/upstream-components.md)。
