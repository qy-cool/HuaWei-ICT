---
name: hwict-git
description: HuaWei-ICT Git 提交规范。当在 HuaWei-ICT 项目目录下进行 Git 操作时自动加载。触发场景：提交代码、创建分支、编写 commit message、合并代码。
when_to_use: 提交代码 / 创建分支 / 编写 commit message / 合并代码 时
user-invocable: true
---

# HuaWei-ICT Git 提交规范

## 1. Commit Message 格式

遵循 Conventional Commits 规范：

```
<type>(<scope>): <subject>

<body>

<footer>
```

### type 类型

| type | 说明 |
|------|------|
| feat | 新功能 |
| fix | 修复 bug |
| docs | 文档变更 |
| style | 代码格式（不影响逻辑） |
| refactor | 重构 |
| perf | 性能优化 |
| test | 增加/修改测试 |
| chore | 构建/工具/依赖变更 |
| ci | CI/CD 变更 |

### scope 范围

| scope | 说明 |
|------|------|
| server | 后端服务（Java Spring Boot） |
| agent | Go 采集 Agent（`agent/`） |
| android | Kotlin/Compose 客户端（`android/`） |
| web | Web 前端（React） |
| skills | 项目级 skill 与决策笔记 |
| docs | 文档（含比赛材料） |
| deploy | 部署与云资源相关 |
| competition | 比赛报名、材料与留痕 |
| ci | CI 工作流与 GitHub 仓库配置（`.github/`） |

### 示例

```
feat(server): 添加用户登录接口

实现 JWT 认证，包含登录和注册接口。
Token 有效期 72 小时，支持 Redis 黑名单。

Closes #1
```

```
fix(web): 修复分析结果页图表不实时更新的问题
```

```
docs(readme): 更新项目 README 和部署文档
```

## 2. 分支命名

| 分支类型 | 命名格式 | 示例 |
|------|---------|------|
| 主分支 | main | main |
| 集成分支 | preview | preview（PR 的合入目标） |
| 功能分支 | feat/功能描述 | feat/server-auth |
| 修复分支 | fix/修复描述 | fix/web-chart-update |
| 文档分支 | docs/文档描述 | docs/api-doc |
| 部署分支 | deploy/部署描述 | deploy/k8s-setup |

## 3. 提交粒度（小步快跑）

- 一个 commit 只做一件事
- 完成模块后应形成独立提交，但是否执行由用户明确授权
- 模块功能包括但不限于：后端接口模块、数据库迁移模块、认证模块、核心 AI 能力模块、前端页面模块、文档阶段成果、比赛材料阶段成果
- **PR 一律合入 preview 分支**（不直接合 main）：功能分支开发，PR 目标为
  preview，由队长批准合入；preview 阶段性稳定后，由队长从 preview 合入
  main，main 始终保持可部署
- 提交前确保代码能编译通过
- 提交前的检查清单见第 4 节

## 4. 前置检查（commit / push 前必看）

- `git status` 确认 working tree 干净或只含本模块预期改动
- `git diff` 确认无意外修改
- 目标分支正确：PR 目标为 preview（不是 main）；本地不直接推 main 或他人分支
- remote 配置正确
- `git config user.email` 与 GitHub 账号绑定邮箱一致，否则提交不归属账号、显示 unverified
- 未跟踪文件已被有意处理（不是构建产物或敏感文件）
- 没有 `.env` / 密码 / Token / 证书 / 私钥 / 数据库备份

## 5. 其他

- 不要提交 `node_modules`、`build`、`.env` 等文件
- 不要提交包含密码/密钥的配置文件
- 使用 `.gitignore` 排除不需要版本控制的文件
- pre-commit 钩子（如有）失败时必须调查根因，不得使用 `--no-verify` 绕过
- prefer 提交而不是 amend（保留清晰的 commit 历史）
