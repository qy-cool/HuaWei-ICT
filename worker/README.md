# Pi 编码节点

开发节点运行固定版本的 Pi SDK，使用企业配置的 OpenAI Chat Completions 兼容端点。每个任务生成独立 Git 索引、文件工作区、会话和 `changes.patch`。人工工作区原有未提交改动继续保存在原目录。

## 接入管理台

1. 安装 Node.js 24+ 和 Git。在本目录执行 `npm ci --ignore-scripts`。在中控把开发节点分配给项目的 dev 或 test 环境，取得该节点有效的 Agent Token。
2. 用独立系统账户运行编码节点。项目仓库由管理员维护，工作目录对该账户可写，模型和节点凭据文件仅授权该账户读取。配置目录、运行目录和凭据文件均放在业务仓库外。不同企业信任边界使用独立账户或虚拟机。
3. 管理员建立两个 JSON 文件。策略文件确定允许提交的来源、模型、源码范围及可选模式。模型只看到授权文件及任务的明确需求。首次使用先拉取项目对应的受信任分支。

```json
{
  "repository": "/srv/sources/orders",
  "ref": "refs/heads/main",
  "runDirectory": "/var/lib/cloide-pi/orders",
  "files": ["README.md", "src/orders/Order.java"],
  "confirmFiles": ["src/orders/Order.java"],
  "modes": ["build", "guarded"],
  "model": "enterprise-code",
  "modelUrl": "https://model.example.invalid/v1"
}
```

节点接入文件把一个仓库别名绑定到一个项目、节点、策略及两个凭据文件。一个进程处理一个绑定，同一时刻执行一个任务。

```json
{
  "controlUrl": "https://control.example.invalid",
  "serverId": 24,
  "projectId": 7,
  "repository": "orders",
  "policyFile": "/etc/cloide-pi/orders-policy.json",
  "nodeTokenFile": "/etc/cloide-pi/node-token",
  "modelTokenFile": "/etc/cloide-pi/orders-model-token"
}
```

4. 前台运行 `node src/node.mjs /etc/cloide-pi/orders-node.json`。进程出站访问中控的 `POST /api/coding-agent/poll` 和 `/events`，逐请求提交节点凭据。中控公网入口使用 HTTPS，回环 HTTP 仅供同机测试。
5. 在项目管理台的编码任务页选择节点，输入管理员登记的仓库别名、40 位提交 SHA 和需求。计划模式只读。变更前确认会展示该文件的拟写入内容，自主和完全访问都遵循节点允许的模式、文件清单及强制确认项。成员可查看输出、任务状态和最终 Git 补丁，审批与取消由发起人或项目负责人执行。

## 授权、限额与恢复

每个项目显式授权最多 32 个 UTF-8 文件，每个文件最多 64 KiB。来自仓库的 Git 配置应由节点管理员维护。工作树只从 Git 对象读取已声明的常规文件，拒绝符号链接、子模块及越界路径。SDK 仅装配本项目的读写工具。运行最多 12 轮，每次模型请求的输出预算为 4096 tokens，任务有 15 分钟总时限。测试和发布应走各自的受控节点作业。编码任务结束状态表示开发者可以审查已有差异。

中控在每次派发和续租时核验发起人、项目及节点。节点读取源码、准备修改及落盘前再次续租。单次审批绑定任务、路径、新旧内容摘要和审批人。用户断开浏览器后可重新进入同一任务继续审查。网络中断会停止新的文件操作，超过租约的任务由中控标记待核实。节点意外终止可能保留本地工作树和未传回的事件，管理员应核对持久工作目录中的补丁与会话。

## 单任务命令行

`node src/main.mjs /etc/cloide-pi/orders-policy.json` 从受信任父进程取得 `CLOIDE_MODEL_TOKEN`。每行 stdin / stdout 是一个 JSON 对象。父进程应保持 stdin 开启并按 `seq` 收集事件。

- 首帧使用 `{"type":"start","taskId":"<UUID>","baseSha":"<40位SHA>","prompt":"需求","mode":"build","plan":false}`。
- `approval` 帧提供 `approvalId`、`path`、`content`、`beforeHash`、`afterHash`；确认后发送 `{"type":"decision","approvalId":"<对应UUID>","allow":true}`。发送 `{"type":"cancel"}` 或关闭输入会取消任务。
- `finished` 返回 `status`、`baseSha`、`patchPath`。使用 `type: "resume"`、相同的任务 ID、基准与策略，在独立进程中续接既有 Pi 会话并还原已暂存文件。恢复只能保留或收窄原权限。异常终止遗留 `active.json` 时，管理员核对 PID、工作目录与进程确已退出后才能移除该任务的锁。

执行 `npm test` 会启动只监听回环的模拟模型，使用真实 Pi SDK、Git 和子进程。SDK 来自 [Pi 官方仓库](https://github.com/earendil-works/pi)，依赖版本及完整性摘要见 [package-lock.json](package-lock.json)。
