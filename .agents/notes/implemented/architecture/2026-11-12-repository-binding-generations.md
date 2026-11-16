# Agent 笔记：仓库绑定代次与提交快照

状态：已实现

## 先说结论

平台为"这次开发、测试、发布用的是哪一份代码"建立了可查证身份：仓库按 GitHub 稳定 remote_id 识别并引入单调递增的绑定代次；开发者把允许分支钉住为不可变提交快照（ref、40 位提交 SHA、树 SHA、代次、策略版本、发起人、解析时间）。分支前移、改名、解绑重绑都不改写历史快照。

## 问题

V44 的 `project_github_repositories` 只有活动行，解绑直接删行：没有代次、没有历史。仓库解绑后重绑给另一项目，旧项目在队列里的任务理论上可借新绑定继续。同时客户端创建任务/测试时自带仓库别名和任意 40 位 SHA，平台无法证明该提交来自本项目授权仓库的允许分支。分支是可移动引用，"测的是 main"在报告落盘后就无法回答指向哪个提交。

## 决策

新增 V51 四张表：绑定历史（`project_repository_bindings`，active 用可空列：1 活动、NULL 撤销，借唯一索引允许多个 NULL 保证同一仓库至多一行活动绑定且多个撤销代次并存）、来源策略（`repository_source_policies` 按 `(remote_id, policy_version)` 保留全部版本，乐观并发）、快照（`source_snapshots` 引用创建时的代次与策略版本）、幂等请求账本（`(项目,发起人,操作,requestId)` 唯一 + 参数摘要）。

解绑从删行改为软删（V44 活动行仍删除以保持旧客户端语义，绑定历史置 NULL 并记录撤销人）；GitHub webhook 的三类撤销（仓库移除、安装删除/挂起、仓库删除）同样软删。重绑生成 `MAX(generation)+1` 新代次；旧代次的快照保留审计但不能再创建新快照。改名按稳定 remote_id 核对后仅同步名称字段。

快照创建流程：鉴权（项目开发权限）→ ref ∈ 允许清单 → 调 GitHub 解析（事务外，网络等待不持库锁）→ `expectedCommitSha` 不符返回 40907 并携带当前实际 SHA（客户端可重读后显式接受）→ 事务内复核绑定代次仍有效后落库。幂等检查先于版本比较：响应丢失后的原样重放返回原快照，即使分支已前移；同键异参返回 40908。

跨项目访问快照/refs/绑定一律 404，不区分"不存在"与"属于别的项目"，避免泄露其他项目的仓库与 ref 存在性。

## 考虑过的备选方案

在 V44 上加 generation 字段：无法表达"同一仓库先后授权给多个项目"的历史，撤销后的行与新绑定行在单行结构里互相覆盖。V44 保持 remote_id 全局唯一绑定一个项目的约束不变（设计文档 A1 明确首版保留），代次历史另立新表，旧表继续作为活动行的兼容视图。

错误响应不携带数据、让客户端再调一次 refs：多一次往返且存在"再读又前移"的循环；直接在 40907 的 data 里返回 currentCommitSha 更省且语义完整。

## 后果

旧客户端（列表、导入、解绑、PR）行为不变；解绑后 `project_github_pulls` 历史保留（原本也不删）。快照表只增不改，撤销代次的快照永久可审计。后续 A2（节点同步）按快照身份取回对象，A3（工作区/编码/测试共用基线）引用 snapshotId，A4（合并反查）以合并提交建快照闭环。

策略版本 0 是隐式默认（仅登记时默认分支），不落库；首次显式设置从版本 1 开始，`expectedPolicyVersion=0` 即可。

## 适用范围

`server/src/main/java/cloide/server/module/repository/`、`server/src/main/resources/db/migration/V51__repository_source_identity.sql` 与[仓库契约](../../../../docs/openapi/openapi-repository.json)。

## 验证

`SourceIdentityTests`（回环 GitHub HTTP 夹具 + H2，V44+V51）：A1-01 分支前移冲突且 data 携带实际 SHA、原样重放返回原快照、同键异参冲突；A1-02 跨项目 404 不泄露、解绑后旧快照 bindingActive=false 且禁止新建、重绑产生第 2 代次且历史不串联；策略乐观版本与幂等重放不产生新版本行；refs 读时观察与单次批量解析。既有 `GitHubIntegrationTests` 补加载 V51 后全绿。

真实 MySQL 方言验证：`RepositorySourceIdentityMySqlValidationIT` 覆盖 V51 四表建表、可空 active 唯一键（多撤销代次共存且至多一行活动）、(remote_id, generation) 与幂等键唯一性、策略版本复合主键。该 IT 需 `RUN_MYSQL_VALIDATION_TESTS=true`、`DB_HOST=127.0.0.1` 与含 "validation" 的隔离库名，经 `./mvnw -Pmysql-validation verify` 执行。**2026-11-16 已在真实 MySQL 8.4.13 上运行通过（5/5）**：联调机一次性容器承载隔离库 `cloide_validation_a1`，SSH 隧道回本机执行，空库经 Flyway 全量迁移 V1–V51；结束后容器、镜像、隧道与临时口令均已清理。首跑暴露并修正了 IT 夹具的 INSERT 参数错位（active 占位符错吃 linked_by 值，H2 单测不经过该路径故未拦截），生产代码无此问题。
