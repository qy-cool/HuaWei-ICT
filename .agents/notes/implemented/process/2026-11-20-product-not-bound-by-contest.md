# Agent 笔记：产品主线不受赛事规则束缚

状态：已实现

## 先说结论

Cloide 要做成完整、可长期维护的企业级自托管 AI 软件工程平台。赛程、评分点和参赛工具要求只影响 `docs/competition/` 里的材料适配，不改变架构原则，也不重排现状盘点中的 A1–A5 优先级。平台与云厂商、模型提供商解耦；华为云是可选部署/集成方案和当前试验环境，不是全部基础设施的唯一必须实现。

## 问题

如果产品定位、创新性和部署说明主要写在赛事目录里，并且用「必须使用某云产品」「报名前必须完成某项留痕」来组织叙事，后续开发容易按评分点裁剪架构。同时，把尚未接入的能力写成已经交付、把一次演练数字写成当前基线，会在对外材料和日常文档里造成两套口径。

## 决策

- 产品定位与价值放在 `docs/intro/product-positioning.md`，创新性放在 `docs/tech-summary/innovation.md`，部署与基础设施选项放在 `docs/tech-summary/deployment-options.md`。
- `docs/competition/` 只引用上述事实并做可选的赛事或展示映射。开发工具、开源托管地点等条款以官方原文为准，仓库内转述不写成权威结论，也不作为一般开发前置条件。
- 不因赛事需要改 MIT 许可，不删除第三方许可义务，不提前启动 `web/`。
- 总体规划等历史规划文件只追加现行说明，不把旧文改写成「当时就已经是这个方向」。
- 未实现能力保持规划口径；量化数字必须写明记录日期与范围。

### 正确做法

写新功能或验收时，先更新产品文档或计划、日志，再视需要让赛事材料引用。提到华为云时写清「可选 / 试验环境 / 未接入」。引用 3.31 秒时写明那是 2026-11-12、V1–V50、独立 MySQL 8.4.9 上的备份及恢复演练。

### 不要这样做

不要为了赛题得分把 MaaS、RDS 或某开发工具写成日常开发门禁。不要把 Compose 基线版本和 DevSpace 现场版本混成一句「当前就是某版本」。不要在没有成文依据时把「订单工程 10 任务方案」写成已有共识。

## 考虑过的备选方案

只在赛事文档开头加一句「我们不只是比赛项目」，但把定位和创新性留在 `docs/competition/`。这样主线仍然藏在赛事情景里，根 README 和开发入口也看不到长期维护目标，所以放弃。

把华为云相关段落全部删掉。当前确实有 DevSpace 试验记录，也需要为可选接入保留映射，删除会丢掉有价值的技术说明，所以只把它们降为可选项。

## 后果

开发者从 README、AGENTS 和产品文档就能看到主线；参赛或展示时仍有适配入口。代价是同一事实会出现「主线 + 映射」两处，映射文必须引用主线而不能另写一套数字。

## 验证

在仓库根执行：

- `node .agents/skills/hwict-docs/scripts/check-docs-links.mjs`
- `node .agents/skills/hwict-notes/scripts/verify-agent-notes.mjs`

## 适用范围

- `docs/intro/product-positioning.md`
- `docs/tech-summary/innovation.md`
- `docs/tech-summary/deployment-options.md`
- `docs/competition/`
- `README.md`
- `AGENTS.md`
- `CONTRIBUTING.md`
- `docs/plans/20261112-企业自托管AI软件工程平台总体规划.md`
- `.agents/skills/hwict-docs/SKILL.md`
