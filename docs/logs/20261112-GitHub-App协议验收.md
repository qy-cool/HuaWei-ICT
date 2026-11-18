# GitHub App 仓库与任务 PR 协议验收

2026-11-12 在 Java 21 上运行 [GitHubIntegrationTests](../../server/src/test/java/cloide/server/module/repository/GitHubIntegrationTests.java)，两项集成测试通过。测试使用真实回环 HTTP 服务、H2 MySQL 模式、V44 迁移和临时 RSA 密钥。服务端实测验证 RS256 JWT 的签名、issuer 和到期时间，校验创建分支和 PR 的 installation token 请求绑定了仓库 ID。

| 场景 | 核验结果 |
|---|---|
| 导入企业仓库、从模板生成私有仓库 | 模板端点收到私有标记。相同创建请求幂等，另一个请求不能接管同名仓库。本地项目授权可撤销 |
| 为待审查任务创建提交、分支及 PR | 观察到基准树、任务文件内容、父提交、专用分支名和目标分支符合记录 |
| 远端分支与 PR 已存在，本地记录缺失 | 删除测试库中的 PR 关联后重试，只产生一份远端任务分支及一份 PR |
| 合并投递、篡改与重复投递 | 正确签名将 PR 更新为 merged，篡改原文被拒。相同交付 UUID 只入库一次 |
| 导出参考项目 | 从工作区导出 19 个唯一 ZIP 条目，内含许可证、两端源码、清单和验证程序。ZIP CRC 检查通过 |

本次使用协议夹具和企业模板 API，没有向真实 GitHub 组织创建仓库或提交 PR。企业域名、选定仓库授权、GitHub 安装事件首次投递，以及真实 GitHub Actions 的检查结果需在企业自己的安装中验收。接入步骤见[企业安装手册](../ops/github-app-repositories.md)。
