# 节点仓库测试作业 v1

在已认证的 `/ws/agent` 上沿用现有 [WebSocket 信封](websocket-protocol.md)。开机时只有显式配置仓库清单的开发节点启用此协议。每个节点同时执行一个作业，旧 Agent 可继续采集和告警。

| 帧 | 发送方 | payload / 行为 |
|---|---|---|
| `job.ready` | Agent | `protocol_version: 1`。连接建立后协商，约每 5 秒轮询，并可触发中控重发当前作业或取消。 |
| `job.execute` | 中控 | `job_id` 为小写 UUID，`project_id` 为正整数，`repository` 为本地登记名，`commit_sha` 为 40 位小写十六进制提交，`deadline` 为 UTC RFC3339。重发保持原请求。 |
| `job.accepted` | Agent | `job_id`。节点已把请求写入持久账本。 |
| `job.log` | Agent | `job_id, seq, text`。序号从 1 到 256，单次发送最多 2048 字节的文本，最终报告保留有界输出。 |
| `job.cancel` | 中控 | `job_id`。先于派发到达时写入取消记录，在执行中到达时取消测试。 |
| `job.result` | Agent | `job_id, status, exit_code, output`。状态为 `succeeded`、`failed`、`cancelled` 或 `uncertain`。正常退出保存真实退出码，未获得退出码时为 -1。 |
| `job.result.ack` | 中控 | `job_id`。中控须先保存终态再发确认。Agent 停止主动重发，保留记录供后续重复派发对账。 |

节点按中控地址与节点 ID 分开保存账本，每个作业单独落盘。首次执行先同步临时文件并原子改名，再同步父目录。同一目录只允许一个 Linux Agent 占用。同一 ID 和参数重发时返回此前接单状态或结果。输入发生变化时拒绝执行。进程启动时发现已接单但没有持久终态的记录，会回报待核实。发现损坏记录会停止启用执行器。磁盘写入失败时不启动新测试，已有终态写入失败会保留在内存等待重试。账本含执行摘要与有界输出，由运维按节点生命周期备份，在线手动删除记录会破坏去重。

## 节点能力报告

新版 Agent 在 ready 帧增加可选 capabilities。节点报告系统、架构、逻辑核数、账本所在卷的可用容量、主机可用内存以及经登记的仓库和工具链标签。容量是操作系统观测值。管理员可在清单加入 toolchains 字符串数组（例如 jdk21、maven39）；前端每分钟刷新，只展示当前用户项目的仓库别名。旧 Agent 可继续发送仅含 protocol_version 的 ready 帧。

## 配置开发节点

使用专用非 root Linux 账号，赋予只读仓库访问和私有账本写权限。设置 `CLOIDE_JOB_MANIFEST` 为管理员维护的 JSON 绝对路径。示例路径均需替换为开发节点实际配置。

```json
{
  "state_directory": "/var/lib/cloide/jobs",
  "toolchain_root": "/opt/cloide/toolchains/java-react",
  "timeout_seconds": 300,
  "repositories": [{
    "project_id": 1, "name": "orders",
    "bare_git_dir": "/srv/cloide/mirrors/orders.git",
    "test_argv": ["/bin/sh", "./verify.sh"]
  }]
}
```

本机安装 `bwrap` 和 `prlimit`。工具链根目录使用干净的离线 Linux 根文件系统，包含 `/bin/sh`、Git、测试所需二进制及动态库，预建 `/dev /proc /objects /tmp /work`。镜像、镜像文件权限、仓库镜像更新和测试命令由节点管理员维护。仓库按项目分别镜像，完全收齐 Git objects 和依赖。作业从登记仓库的只读对象目录在临时目录检出精确提交，仅把镜像、Git 对象和临时工作区暴露给测试进程。节点宿主与 Agent 环境变量不会传递给测试。

每次测试开启独立的 PID、用户和网络等命名空间，丢弃 capabilities，挂只读工具链和空只读 `/proc`。工作目录和 `/tmp` 为限容 tmpfs，宿主不保存代码副本。超时上限由清单与作业截止时间共同确定，`prlimit` 限制子进程数、单进程地址空间、文件大小、CPU 时间和文件描述符。企业应为开发节点设置独立的宿主资源边界，尤其是运行整个进程树的内存总量和 CPU 配额。依赖宿主 procfs、联网下载依赖或需要持久工作区的项目应使用其他已验证的开发环境。

## 从本次测试收集制品

仓库清单可增加 `artifact_kind` 为 `java-jar` 或 `static-tar`。管理员登记的命令执行测试与构建后，将成品写入 `$CLOIDE_BUILD_OUTPUT`。节点只绑定一个预留的普通文件到隔离环境，测试退出后由宿主校验大小、计算 SHA-256，并持久记录作业号、项目和提交。如果缺少成品或超过 128 MiB，此作业失败。

让同一非 root 节点账户定期执行 `python3 deploy/agent/publish-build.py --origin https://control.example.org --state /var/lib/cloide/jobs --server-id 2 --token-file /etc/cloide/node-token`。参数使用实际登记值。中控在读取前检查源作业，登记前重查项目与节点授权。回执丢失或中控尚未收妥终态时，下一次上传使用原作业和摘要。界面区分节点证明的制品和用户手工上传的制品。节点已发送的文件由管理员按恢复与重试策略清理。企业签名方独立提供发布签名。

## 验证

在 Agent 目录执行 `go test ./internal/jobs` 校验回执丢失、重启、重复派发、输入冲突、取消和过期。低权限 Linux 开发节点具备 Git、bwrap、prlimit、ldd 后，设置 `CLOIDE_JOB_SANDBOX_TEST=1` 并执行 `go test -count=1 -v ./internal/jobs`。测试创建临时离线工具链和仓库，实际检出提交、运行测试脚本、检验凭据隔离及跨项目拒绝。
