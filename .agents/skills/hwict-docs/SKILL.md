---
name: hwict-docs
description: HuaWei-ICT 文档规范。当在 HuaWei-ICT 项目目录下编写开发计划、开发日志、OpenAPI、协议文档或交接/运维文档时自动加载。触发场景：编写开发计划、开发日志、OpenAPI 契约、协议文档、交接文档、运维手册。
when_to_use: 编写开发计划 / 开发日志 / OpenAPI / 协议文档 / 交接文档 时
user-invocable: true
---

# HuaWei-ICT 文档规范

## 文档目录结构

项目根目录下的 `docs/` 是文档根目录：

```
docs/
├── plans/           # 开发计划 (.md)
├── logs/            # 开发日志 (.md)
├── openapi/         # OpenAPI 规范接口文档 (.json，契约唯一事实源)
├── protocol/        # WebSocket、SSE 等通信协议文档 (.md)
├── handoff/         # 开发/部署交接文档
├── bug-fix/         # 缺陷修复记录
├── difficulty-log/  # 踩坑与难点记录
├── intro/           # 项目介绍材料
├── tech-summary/    # 技术总结
├── ops-manual/      # 部署、升级、备份、安全等运维手册
└── competition/     # 比赛材料（报名、模板、作品信息、留痕）
    └── codearts-trace/  # CodeArts 使用留痕（见 hwict-dev 第 0 节）
```

如需记录本机开发环境说明，脱敏后（密码占位、地址仅回环）存
`docs/ops-manual/`，不得含真实凭据。

## 1. 开发计划 (docs/plans/)

- 所有开发计划写成 `.md` 文件放在此目录下
- 每个计划文件命名格式: `YYYYMMDD-计划标题.md`
- 计划内容应包含：目标、任务清单、预期时间、依赖关系
- 模板见 `references/develop-plan-template.md`

## 2. 开发日志 (docs/logs/)

- 每次开发活动后记录日志，文件名格式: `YYYYMMDD-日志标题.md`
- 日志内容应包含：日期、操作人、改动内容摘要、涉及的文件
- 模板见 `references/develop-log-template.md`

## 3. OpenAPI 接口文档 (docs/openapi/)

- 使用 OpenAPI 3.1 规范（JSON Schema 2020-12），以 `.json` 文件格式存放；
  以 springdoc-openapi 3.x 生成的 3.1.0 为准，空值用类型数组表达（如
  `"type": ["string", "null"]`），不使用 `nullable`
- 文件命名格式: `openapi-{模块名}.json`（如 `openapi-alert.json`）
- 每次新增或修改 API 接口时，同步更新对应的 OpenAPI 文档
- 模板见 `references/openapi-template.json`
- 建议在前端工程配置契约校验脚本（骨架建立后），保证类型定义与后端同步
- 迁移期例外（2026-10-29）：`docs/openapi/` 现为 cloide-shell 迁入的
  3.0.3 快照（含 `nullable`），待后端升级到 springdoc 3.2.0 后重新生成
  3.1.0；当前版本以 `docs/openapi/README.md` 为准

## 4. 协议文档 (docs/protocol/)

- WebSocket、SSE、与大模型服务的交互协议等写成 `.md` 文件放在此目录下
- 文档中的服务器地址示例统一使用 `SERVER_IP_OR_DOMAIN`，不使用真实 IP 或内网 IP

## 5. 比赛材料 (docs/competition/)

- 初赛作品信息收集表（官方模板）及配套素材存放在此
- **五要素素材持续沉淀**（对应初赛评审要点）：方案背景与价值、创新性解读、
  数据集选取与数据处理、关键代码片段解读、可量化效果——开发过程中产生
  相关素材（数据集说明、评测指标、CodeArts 留痕等）随手归档，赛前汇总成
  正式材料
- 量化效果（准确率、时延、效率提升等）以可复现的评测记录为准，不写无依据
  的数字

## 6. 交接与运维文档

- `docs/handoff/` 交接文档命名格式: `YYYYMMDD-主题交接.md`，记录分支状态、前置会话、未完成事项
- `docs/ops-manual/` 运维手册覆盖华为云部署、升级回滚、备份恢复、安全检查
- 手册中的真实 IP/密码/token 不入库（与仓库脱敏惯例冲突的仅本地留存）

## 工作流程

1. 接收开发任务后，先在 `docs/plans/` 创建计划文档
2. 开发过程中，每完成一个功能模块，在 `docs/logs/` 记录开发日志
3. 新增或修改 API 时，同步更新 `docs/openapi/` 中的接口文档
4. 新增或修改 WebSocket/SSE 等通信协议时，同步更新 `docs/protocol/` 中的协议文档
5. 产生比赛五要素相关素材时，随手归档到 `docs/competition/`
6. 阶段性收尾或换人接手时，在 `docs/handoff/` 记录交接文档
7. 确保文档目录的内容保持一致
8. 提交前运行 `node .agents/skills/hwict-docs/scripts/check-docs-links.mjs`，
   确认相对链接与仓库路径引用都存在（PR 上由 CI 自动执行）

## 其他

- Java/Spring Boot 后端编码规范见 `hwict-java` skill
