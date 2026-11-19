# Agent 笔记：节点源码同步的受管 bundle 路线

状态：提议中

## 先说结论

A2 让一台没有任何项目源码的受管节点凭一次 API 请求取回批准的提交快照。路线是**中控生成 Git bundle、节点凭短期授权从中控下载、本地核验后原子安装**：节点全程不接触 GitHub 凭据，长期密钥只进中控的受管同步进程。WSS 上新增独立的 `source.sync.*` 帧族（与 `job.execute` 分开协商），节点在 capabilities 的 actions 里声明 `source.sync.v1` 才会收到派发。同步账本、安装目录与既有作业体系完全分开，重复派发与断网恢复按同一 syncId 查账收敛到唯一结果。

## 问题

已登记的远端仓库不会自动同步到节点：今天要在某台内网节点跑某项目的测试，节点管理员必须提前手工克隆仓库、准备 Git 对象库，每加一个项目、换一台机器都要重复现场操作。直接把 GitHub App 的 installation token 下发给节点可以解决同步，但等于把长期有效的仓库读权限散布到每台节点，撤权滞后且无法保证节点日志与工作区不残留凭据。同步是内容分发不是测试执行，混入 `job.execute` 协议会污染作业语义并破坏旧 Agent 兼容。

## 提案

1. **受管同步进程（中控）**：用 JGit 按 GitHub installation 领取的只读 token，从受信 Git 出口按精确提交 fetch 到受保护的临时裸镜像，把私有 ref `refs/cloide/snapshots/{snapshotId}` 固定到该提交，校验 commit 与 tree SHA 后生成只含该快照可达历史的 Git bundle，按 bundle SHA-256 寻址落盘并登记摘要与字节数。强推、删分支或上游清理导致无法证明固定提交来源时，同步进入 `failed` 且错误分类 `source_unavailable`，原快照保留审计。
2. **状态机**：`queued → preparing → transferring → verifying → ready`，取消走 `cancelling → cancelled`。节点离线超过截止时间加宽限后由定时扫描落 `uncertain` 终态，节点回归后的真实回执可覆盖核实——复用持久作业已验证的兜底模式。
3. **短期授权**：节点用自身凭据（与制品上传相同的 `X-Agent-Token`）领取绑定 sync、节点、绑定代次与有效期的授权，服务端只保存 43 位密钥的 SHA-256；每次内容下载请求都重查撤权与期限，支持 `Range` 断点续传，日志只记授权 ID、时间与结果。
4. **原子安装（Agent）**：按 `(project_id, repository_id, snapshot_id)` 在同步根目录分目录，临时目录完成 bundle 导入与 `rev-parse` 级核验（commit、tree、bundle SHA-256、字节数）后同文件系统原子更名。相同 syncId 且相同信封摘要的重复送达查账返回原结果；同 ID 换摘要、目录或代次记录冲突并拒绝。
5. **独立账本**：同步账本与作业账本分开落盘，同构沿用「先写临时文件再原子改名、重启后无终态回报 uncertain」的既有模式。

## 考虑过的备选方案

- **节点直连 GitHub 同步**（中控只下发 token）：被否。长期读权限散布到每台节点，撤权要逐节点回收，且无法向审计证明节点日志和工作区不留长期密钥；对无法直连公网 Git 出口的内网节点也不可用。
- **复用 `job.execute` 帧、把同步当作一种作业**：被否。作业协议承载的是沙箱化测试执行（超时、日志、退出码），同步是幂等内容分发（续传、摘要校验、目录安装），输入输出与故障语义都不同；混用会让旧 Agent 遇到未知输入且调度语义纠缠。
- **分发 tarball 而非 Git bundle**：被否。tarball 丢失对象级校验（裸对象库上无法 `rev-parse` 验证 commit/tree），也无法与既有 `bare_git_dir` 检出工作流衔接。
- **外部 git 命令而非 JGit**：被否。中控部署将引入 git 二进制与版本依赖，回环测试也要外部 git；JGit 纯 Java、Java 21 可用。代价是新增一个依赖进入 SBOM，发行许可复核时需补核。

## 验收标准

- A2-01 半包与凭据过期：下载中断后同一 syncId 续传或重下，最终只有一个 ready 目录；中控与节点双侧核对 commit、tree、bundle SHA-256 与长度；日志和测试环境不含令牌。
- A2-02 丢确认与错误节点：节点完成安装后丢弃首个确认；第二节点提交第一节点的回执被拒；原节点可重放且安装标记只有一次；证据不足进入待核实。
- A2-03 空间不足或损坏：容量上限或传输中改变单字节返回明确失败；没有可执行的半成品；原 ready 快照仍可读取；清理后可明确重试。
- 节点侧断言按设计文档在无访问令牌的校验子进程内执行 `rev-parse` 与 `sha256sum` 比对。

## 当前进展（2026-11-18）

实现已落地并通过单节点 + 回环夹具验证（本地 Git 对象库充当受信出口、模拟 GitHub 与节点）：中控侧 `SourceSyncTests` 8 项（H2 + 回环 GitHub + 真实 JGit fetch/bundle 全链），Agent 侧 `internal/syncs` 10 项（真实 git bundle + 回环下载服务，覆盖 A2-01/02/03）。全量回归 931 项 Java 与 Go 全包通过。尚欠：真实 MySQL 的 V53 表结构 validation IT、双机真实验收与企业受信 Git 出口对接——完成后转 implemented。

## 风险

- JGit 生成的 bundle 与节点 git 版本的兼容性：bundle v2 格式长期稳定，风险低；若企业节点 git 过旧，按容量/不支持分类返回明确错误。
- 大仓库 bundle 体积：首版按字节数限额（默认与制品限额同量级），超限返回明确容量结果，不实现增量对象协商。
- H2 与 MySQL 的 JSON/时间语义差异已有先例处理方式，V53 表避免 JSON 列即可不引入新的方言差异。

## 适用范围

`server/src/main/java/cloide/server/module/repository/`、`server/src/main/resources/db/migration/V53__*.sql`、`agent/internal/syncs/`、[节点作业协议](../../../../docs/protocol/node-jobs-v1.md)与[仓库契约](../../../../docs/openapi/openapi-repository.json)。
