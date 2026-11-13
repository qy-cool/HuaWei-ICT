# Linux 应用发布节点

中控保存发布意图。每个服务配置一个专用非 root 发布账户，在该账户的私有持久目录运行 `python3 node.py /etc/cloide-release/orders.json`。一次调用最多处理一个目标，企业可以用 systemd 用户 timer 定期调用。对同一状态目录并发调用由文件锁串行化。需要 Python 3.9 以上、OpenSSL 和可信 HTTPS 中控。

```json
{
  "control": "https://control.example.org",
  "server_id": 2,
  "token_file": "/etc/cloide-release/agent-token",
  "project_id": 1,
  "environment": "test",
  "service": "orders-web",
  "kind": "static-tar",
  "state": "/srv/cloide-release/orders-test",
  "signatures": "/etc/cloide-release/signatures/orders-test",
  "public_key": "/etc/cloide-release/trust/orders.pem",
  "active_probe": "http://127.0.0.1:18400/index.html",
  "probe_contains": "orders-root"
}
```

1. 管理员创建状态目录并交给发布账户。应用进程、工作区和测试进程只读发布文件；凭据、公钥、签名目录由管理员独立维护。令牌文件只含 Agent 登记时返回的节点令牌。
2. 发布账户向中控拉取匹配项目、环境、服务和节点的任务。单个制品及解包内容上限各为 256 MiB。静态站点把可发布内容打包为 tar 或 tar.gz，包根含 index.html。Web 服务器的站点根目录指向状态目录下的 active。中控制品列表绑定作业、来源提交及 SHA-256。生产环境只允许管理员提升由实际构建节点登记且已在测试环境通过同服务探针的制品。人工上传仍可在开发、测试环境使用。
3. 企业签名端执行 `openssl dgst -sha256 -sign release-private.pem -out SHA256.sig artifact`，将签名以实际摘要命名，单独投送到该项目的签名目录。将公钥固定在节点配置中。发布账户没有签名私钥。节点先检查字节摘要，再校验签名，将内容放入新版本目录。
4. 静态站点由原子符号链接切换。业务探针检查回环 HTTP 端点、200 响应和约定内容；配置一个能代表关键业务的响应片段。探针失败会恢复上一活动链接并重验。
5. Java 服务使用同一格式，将 kind 改为 java-jar，并加 candidate_unit、active_unit、candidate_probe。两个预装的用户级 systemd 单元分别读取 candidate/app.jar 和 active/app.jar，使用独立端口。候选单元应使用与正式消费者、定时任务和数据迁移隔离的配置。候选就绪后停机，再切换活动版本并重启正式单元。此模式切换会产生重启窗口。
6. 回执在发送前落盘。重复发来的同一代次仅返回原结果。外部效果无法确认时上报 uncertain，负责人核对活动链接、实际进程和业务数据后按发布协议补充证据。不同项目或环境使用不同状态目录。该执行器不自动清理旧版本。

签名文件投送、HTTPS、静态服务器/Java 用户单元及其业务密钥由安装方准备。多节点滚动切流、后台消费者交接、数据库兼容窗口须按总体规划另行验收。可在 Linux 从本目录运行 `python3 -m unittest -v test_node`，测试创建临时签名密钥、站点和探针，验证损坏制品、目录越界、切换失败和丢回执。
