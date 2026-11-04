# GitHub 仓库设置清单

本文件记录仓库侧（GitHub Settings）必须人工开启的配置。仓库内的 CI 与流程都
假设这些设置已就位；重建仓库或换管理员时按本清单逐项执行。

## 当前状态（2026-10-29 实测）

| 项 | 现状 | 说明 |
|---|---|---|
| 可见性 | public | 赛题入围后需开源，内容按可公开标准写 |
| 默认分支 | main | |
| License | MIT（已提交到 preview，合并 main 后 GitHub 识别） | 此前 API 显示 `license: null` |
| GitHub Pages | 已上线（2026-10-28） | Source = GitHub Actions；`https://genhao0823.github.io/HuaWei-ICT/` 可访问 |
| main 分支保护 | 已启用 | PR + 1 approve + Code Owner review + 六个状态检查；禁止强推与删除 |
| preview 分支保护 | 已启用 | PR + 六个状态检查；0 approval，队长可直接合并自己的 PR |
| Actions | 已启用（allowed_actions=all） | |
| CODEOWNERS | `* @genhao0823` | 已随 main 的 "Require review from Code Owners" 生效 |

> 2026-10-29 新增的 `Verify Server` / `Verify Web` / `Verify Agent` /
> `Verify Android` 尚未加入分支保护；PR 跑绿后补入，并把本文所有状态检查
> 数量改为六个。四个工作流都不加路径过滤，目录不存在时上报跳过状态。

## 已完成配置（2026-10-28）

- **Pages**：Source 设为 **GitHub Actions**。合入 `main` 会触发
  `deploy-agent-notes-board.yml`，看板地址
  `https://genhao0823.github.io/HuaWei-ICT/`；2026-10-28 首次部署成功
  （run `40691217782`），站点 `status` 仍可能为 null，以页面可达为准。
- **main 保护**：Require a pull request before merging（1 个 approve）、
  Require review from Code Owners、Require status checks（`Verify Agent Notes`、
  `Verify Docs Links`、`Verify Server`、`Verify Web`、`Verify Agent`、
  `Verify Android`，要求分支最新）、禁止 force push 与删除、要求解决会话。
  这些校验工作流不加路径过滤，每次 PR 都能上报状态。
- **preview 保护**：Require a pull request before merging（0 approval）、
  同样要求六个状态检查与分支最新、禁止 force push 与删除。复用旧分支开 PR 前
  先同步最新 `preview`，确保 `verify-docs.yml` 在分支中。
- 两个分支都没有强制管理员遵守（`enforce_admins=false`）：队长在紧急情况下
  可以绕过；待第二名协作者加入后，main 的 1 approval 与 Code Owner review
  才会实际约束普通 PR。
- **Actions 权限**：实测 `default_workflow_permissions=read`、
  `can_approve_pull_request_reviews=false`，无需再改。
- **首次发布**：当前只有管理员一名协作者，`preview → main` 的 review 要求需
  管理员 bypass；状态检查仍须通过。加入第二名协作者后再验证一次正常 approve。

## 待办

1. **Secrets**：华为云 AK/SK、MaaS API Key 只放 Environments/Secrets，命名统一
   为 `HUAWEI_AK` / `HUAWEI_SK` / `MAAS_API_KEY`，不出现在仓库文件与日志中。
2. **协作者**：把队员加为 write 权限协作者；main 的 1 approval 与 Code Owner
   review 需要非作者的协作者才能真正满足。
3. **本地 Git 身份**：队员本地 `git config user.email` 必须与 GitHub 账号绑定
   邮箱一致，否则提交不归属账号（见 `.agents/skills/hwict-git/SKILL.md` §4）。

## 本地工具目录

`.zcode/` 等本地 AI 工具目录已在 `.gitignore` 中忽略，且仓库历史中从未入库。
不要用 `git add -f` 强行加入；需要共享的规范放 `.agents/skills/`。
