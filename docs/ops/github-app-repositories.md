# GitHub App 仓库接入

每套中控可配置一个 GitHub App installation 及其所属组织。企业平台管理员将具体仓库授权给一个项目。有项目读取权限的成员可通过 API 查看连接，开发者可以为自己的可审查编码任务创建拉取请求。项目负责人可以发布本项目其他成员的任务。

## 企业安装

1. 在企业组织安装 GitHub App。导入需要 Metadata read。任务分支和 PR 需要 Contents write 和 Pull requests write。从模板创建仓库还需要 Administration write 和 Contents read。只有企业启用新建入口时才需授予创建权限。安装需要能访问选定模板和目标仓库。[GitHub 安装令牌](https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/generating-an-installation-access-token-for-a-github-app) · [从模板创建仓库](https://docs.github.com/en/rest/repos/repos#create-a-repository-using-a-template)
2. 将 webhook URL 指向中控 HTTPS 的 `/api/integrations/github/webhook`。设置独立的高熵秘密，选择 pull_request、repository 以及相关安装事件。中控按原始字节验证 HMAC-SHA-256，校验安装 ID，按 delivery UUID 去重。合并或关闭 PR 会更新任务记录，移除仓库和停用安装会撤销本地连接。[GitHub Webhook 签名](https://docs.github.com/en/webhooks/using-webhooks/validating-webhook-deliveries)
3. 使用企业密钥系统向 Spring 注入下列属性。私钥文件仅中控服务用户可读。GitHub 下载的 PKCS#1 RSA 私钥可以用 `openssl pkcs8 -topk8 -nocrypt -in app.pem -out app.pk8` 转成 PKCS#8。运行时从文件读私钥生成短时 RS256 JWT，以 installation token 调用 GitHub。

| Spring 属性 | 配置 |
|---|---|
| `cloide.github.app-id` | GitHub App ID |
| `cloide.github.installation-id` | 企业组织的安装 ID |
| `cloide.github.owner` | 允许创建和导入的 GitHub 组织名 |
| `cloide.github.private-key-file` | 中控可读的 PKCS#8 PEM 绝对路径 |
| `cloide.github.template` | 企业维护并设为 template repository 的 `组织名/仓库名` |
| `cloide.github.webhook-secret` | 与 GitHub 配置对应的秘密 |
| `cloide.github.api` | 默认为 `https://api.github.com`。GitHub Enterprise Server 可填 HTTPS API 地址及其 `/api/v3` 路径 |

## 新建与导入

在仓库根目录执行 `python scripts/export-order-template.py --output orders-template.zip`，会导出订单 Java API、PostgreSQL、Redis、RabbitMQ 四服务模板、部署清单、验证程序和许可证。指定 `--ref <标签或提交>` 可从已提交的历史版本导出。将压缩包内容放入企业的模板仓库，启用 GitHub 的 Template repository 选项，将其全名配置到中控。

平台管理员通过项目仓库 API 选择新建或导入。新仓库默认私有，GitHub 根据企业模板创建。创建请求保留 UUID，同一请求在 HTTP 中断后可核对所有者、仓库名、私有状态和创建标识继续操作。导入需要安装对目标仓库有权限。将仓库同名别名及固定基准 SHA 配置到受管开发节点后，即可用于现有编码运行时。解除项目授权保留 GitHub 上的仓库和提交。

## 发布任务 PR

任务进入 `ready_for_review` 后，选择对应仓库及任务并创建 PR。中控从已记录的 change 事件核对每个文件的基准 SHA-256、连续变更和最后内容，从 GitHub 基准树创建 Git tree / commit，再创建 `cloide/task-<任务 UUID>` 分支。既有文件保留普通或可执行文件模式。首轮支持至多 32 个允许路径的 UTF-8 文本文件，每个 64 KiB。计划模式、无变更任务、缺少完整内容的旧事件、冲突的基准和不一致的已有任务分支都会提示无法发布。默认分支前移时重新基于当前提交运行编码任务。

成功后 API 返回 PR 编号、任务和提交，可由企业在 GitHub 进行审查、CI 和合并。远端分支或 PR 已建立但本地记录丢失时，重试同一任务将核对远端提交树、父提交和 PR 头。实际生产安装所需的 GitHub 密钥由企业提供。本仓库的协议测试使用回环 HTTP、一次性 RSA 密钥和真实签名验证。[GitHub Git trees](https://docs.github.com/en/rest/git/trees#create-a-tree) · [GitHub Pull requests](https://docs.github.com/en/rest/pulls/pulls#create-a-pull-request)
