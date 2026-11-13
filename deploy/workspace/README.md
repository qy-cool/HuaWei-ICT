# 接入企业自托管 code-server 工作区

企业为每个工作区登记独立域名、所有者、开发节点和持久卷。受管实例由节点管理器启动，所有者在「我的工作区」可打开 IDE、请求休眠或恢复。旧版登记的独立实例仍可通过同一网关访问，进程继续由企业自己的服务管理器维护。

## 主机与端口

| 位置 | 示例 | 用途 |
|---|---|---|
| 平台浏览器管理台 | `https://console.example.test` | 登录、获取工作区列表和个人入场票据 |
| 工作区 HTTPS 入口 | `https://orders.ide.example.test` | 单一所有者、独立域名和证书 |
| 中控网关主机 | `127.0.0.1:16400` | 当前工作区专属 session-guard |
| 中控网关主机 | `127.0.0.1:16401` | 私网连接落点，固定通往该实例 |
| 开发节点 | 独立 VM 的 `127.0.0.1:18081` | code-server、开发者终端和持久目录 |

每个工作区使用独立的虚拟机或等效隔离边界。开发用户必须无法访问网关密钥、其他工作区网络、文件卷、中控数据库或宿主机管理接口。回环端口由同一网络命名空间内的进程共享；不要在包含不可信工作区代码的宿主网络命名空间授权裸回环实例。

## 配置与登记

1. 企业按依赖清单固定、校验并安装 code-server 到开发节点。以非特权工作区用户在隔离环境内运行以下命令，把工作树和用户数据置于持久卷。公网不开放 IDE 端口。管理员预先配置私网专用连接，将中控回环端口 16401 固定映射到这个实例的 18081。每个映射只承载一个固定目标。核对映射、节点 ID 和域名属于同一工作区。code-server 支持在启动时关闭端口代理。[上游 CLI 参数](https://github.com/coder/code-server/blob/main/src/node/cli.ts)

   ```sh
   code-server --bind-addr 127.0.0.1:18081 --auth none --disable-proxy \
     --disable-update-check --user-data-dir /work/ide-data /work/repository
   ```

2. 给中控配置 `PROJECT_ISOLATION_ENABLED=true`、`WORKSPACE_DOMAIN=ide.example.test`、`WORKSPACE_CONSOLE_ORIGIN=https://console.example.test`。设置独立随机的 `WORKSPACE_GATEWAY_KEY`（例如由密码管理器生成至少 32 字节熵）。控制面、Nginx 和 session-guard 配置使用同一把密钥。管理台与工作区域名位于不同的 DNS 分支。三项工作区环境变量全部留空时，工作区入口关闭。
3. 由运维从 `deploy/workspace/session-guard/` 执行 `go build -trimpath -o /usr/local/bin/cloide-workspace-guard .`，在中控网关主机启动每工作区一个守护进程。由系统服务管理器通过私有环境文件传入密钥。Go 程序仅接受显式回环 IP 和端口，禁用环境 HTTP 代理。

   ```sh
   cloide-workspace-guard -host orders.ide.example.test \
     -listen 127.0.0.1:16400 -ide 127.0.0.1:16401 -control 127.0.0.1:18080
   ```

4. 在 Nginx 主配置的 `http` 上下文安装 [站点模板](workspace.nginx.conf.example)，替换固定域名、证书和回环端口。把 `proxy_set_header X-Cloide-Gateway-Key "实际随机密钥";` 单独写入模板引用的私有文件，只允许 Nginx 管理账户读取。确认 Nginx 的 `nginx -V` 包含 `--with-http_auth_request_module`，执行 `nginx -t` 后加载。管理台的公共虚拟主机拒绝 `/api/workspace-gateway/`，Java 后端与 guard 端口只允许受信网关进程接入。Docker 环境的示例回环地址指宿主机上仅绑定至回环的端口。避免记录请求正文、Cookie 或代理鉴权头。[Nginx 鉴权子请求](https://nginx.org/en/docs/http/ngx_http_auth_request_module.html)
5. 管理员向 `POST /api/projects/{projectId}/workspaces` 提交下列 JSON，携带本人管理台 Bearer JWT。节点应已分配至本项目的 `dev` 或 `test`，所有者应为审核通过的开发者、项目所有者或平台管理员。保存响应中的 UUID，供撤权和审计使用。同一个域名永久绑定一个工作区，停用后继续保留。

   ```json
   {"serverId":13,"ownerId":2,"name":"订单系统开发","slug":"orders"}
   ```

6. 所有者从项目管理台进入工作区。平台生成 60 秒的一次性入场票据，经浏览器顶层 POST 兑换为有效期 30 分钟的 Secure、HttpOnly、SameSite=Strict、host-only Cookie。查询参数和重放均无法兑换。管理台 JWT 留在管理台页面。会话过期后从管理台重新打开，终端能否重连取决于 code-server 和实际后台进程状态。

## 验收、撤权与运维

- 以所有者打开 IDE、编辑文件、运行一次项目测试并保存结果。关闭浏览器再打开，检查未提交文件和终端状态。由其他成员尝试打开同一工作区，检查控制台、票据、请求和升级连接的拒绝状态。
- 保持一个终端连接。撤销项目成员、降为只读或解除节点分配后，新请求在鉴权处失败。guard 对已经升级的连接和长 HTTP 响应每 10 秒重新查验，单次超时 3 秒。中控无响应也会关闭连接。重新授予项目权限后，原票据和会话已经清除，用户需重新打开 IDE。连接关闭与节点后台任务取消是独立操作。
- 管理员通过 `DELETE /api/projects/{projectId}/workspaces/{id}` 永久关闭登记。保留文件时先核对工作树和持久卷再停止 code-server。恢复需使用新标识和新域名登记，防止旧浏览器存储或 service worker 继承其他人的权限。
- 受管工作区的休眠会停止 IDE 及其终端进程，已保存文件和 Git 状态随工作区卷保留。独立工程作业使用自己的运行进程和目录。永久停用后，管理器会收到休眠目标，持久卷由管理员备份和清理。
- 实例和 guard 的目标由运维登记和固定。代码仓库、网页请求及模型输出均无权修改映射。密钥轮换应同时更新中控、Nginx 私有文件与各 guard，对已有连接逐一复核。日志仅记录工作区、时间和状态。接入企业节点前，在隔离节点验证扩展、子资源域名、文件上传大小和工作负载资源配额。

## 受管实例

管理员在工作区页面登记受管工作区，或向登记接口提交 `managed: true`。在对应节点为该 UUID 配置 [节点管理器](node.py)。每个管理器只接受与本地配置的工作区、项目、节点和域名同时匹配的状态。它通过出站 HTTPS 查询期望状态，每两秒确认当前代次。中控在取得本代运行回执后才允许签发或续用票据。45 秒未收到节点心跳的实例需要重新确认。中控恢复模式返回只观察指令，冷启动的节点会报告休眠。

```json
{
  "control": "https://console.example.test",
  "workspace_id": "00000000-0000-4000-8000-000000000001",
  "hostname": "orders.ide.example.test",
  "project_id": 7,
  "server_id": 13,
  "token_file": "/var/lib/cloide-manager/private/node.token",
  "lock_file": "/var/lib/cloide-manager/private/workspace.lock",
  "gateway_socket": "/var/lib/cloide-manager/private/gateway.sock",
  "rootfs": "/srv/cloide/toolchains/ide-arm64",
  "repository": "/srv/cloide/volumes/orders/source",
  "ide_home": "/srv/cloide/volumes/orders/home",
  "socket_directory": "/run/cloide/orders",
  "proc_mode": "private"
}
```

在只读工具链根目录安装经 SHA-256 校验的官方 code-server 到 `/opt/code-server`，加入运行时依赖、Git、shell 与应用需要的已批准工具。提前建立 `/home/ide`、`/work`、`/run`、`/proc`、`/dev`、`/tmp` 挂载点。根目录及工具链由管理员维护。工作树、IDE 用户数据和 socket 目录使用三个独立的、仅指定管理账户可写的目录。凭据、管理配置和锁文件置于这三个目录及工具链之外。

Linux 管理器要求非 root 账户、Python 3.8+ 和可用的非特权 bubblewrap 命名空间。在独立 VM 或企业设置了总 CPU、内存、存储和进程数配额的节点上运行 `python3 node.py /etc/cloide/orders-workspace.json`。管理器使用独立进程、用户、网络和挂载命名空间。用预置的私网隧道把 guard 的固定回环上游映射到管理器私有的 `gateway_socket`。管理器锁定 IDE 的 socket inode 并检查进程 UID 后才转发连接，工作区无法替换隧道目标。容器禁止挂载私有 procfs 时可用 `proc_mode: "empty"`，需要 procfs 的工具应在支持该能力的开发节点验证。节点进程退出时停止 IDE，浏览器关闭不会触发休眠。普通工作区文件不携带管理凭据。
