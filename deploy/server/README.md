# cloide-shell Java 云端部署

本手册覆盖计划 T5 的 Java 运行资产，目标为 OpenCloudOS 9.6、Nginx 和本机 MySQL。它不执行真实部署，也不替代生产变更确认。

## 运行边界

- Java 以 `cloide` 专用非 root 用户运行，只监听 `127.0.0.1:18080`。
- Nginx 是唯一公网入口；只对公网开放 HTTP/HTTPS 所需端口，`18080` 和 MySQL `3306` 不得开放公网。
- Java 终端输入、输出和 Agent Token 不得写入环境模板、日志、Nginx 配置或运维记录。
- 生产秘密只保存在 `/etc/cloide/server.env`，权限必须为 `root:root` 和 `0600`。
- 当前终端中继只支持单 Java 实例。不得在负载均衡器后横向启动多个实例。

## 上线前确认

在目标主机执行前，先由负责人明确确认以下事项：

1. 目标主机、域名、Nginx 站点配置文件和 Java JAR 发布目录。
2. Nginx 站点配置备份路径，且备份文件已存在、非空并可读。
3. MySQL 备份工具、生产库名称、备份路径、可用磁盘空间和恢复责任人。
4. 发布窗口、回滚窗口和允许的服务中断时间。
5. 防火墙规则，确认 Java `18080` 与 MySQL `3306` 不会暴露到公网。

生产数据库备份、Nginx 配置修改、Flyway 迁移和服务重启都属于真实环境操作，必须在上述范围确认后单独执行。

## 构建发布包

在受信任的构建环境、`server` 目录执行：

```bash
./mvnw clean package
sha256sum target/cloide-server-0.0.1-SNAPSHOT.jar
```

将经校验的 JAR 上传到目标主机的版本目录，例如 `/opt/cloide/releases/RELEASE_ID/`。保留当前运行版本，发布目录不可由 `cloide` 用户写入。

## 首次安装

以下命令是人工操作步骤。执行前必须替换路径并确认目标主机；不要将真实密钥放到命令行历史或终端录屏中。

```bash
sudo useradd --system --home-dir /var/lib/cloide --shell /sbin/nologin cloide
sudo install -d -o root -g root -m 0755 /opt/cloide/server
sudo install -d -o root -g root -m 0700 /etc/cloide
sudo install -d -o cloide -g cloide -m 0700 /var/lib/cloide
sudo install -m 0600 -o root -g root deploy/server/cloide-server.env.example /etc/cloide/server.env
sudo install -m 0644 deploy/server/cloide-server.service /etc/systemd/system/cloide-server.service
```

在受控编辑器中填写 `/etc/cloide/server.env` 的实际值。必须保持 `APP_ENV=prod`、`SERVER_ADDRESS=127.0.0.1`，使用 API 工具的部署可以留空 `CORS_ALLOWED_ORIGINS`；接入独立客户端时填写企业授权的 HTTPS 来源。

创建当前版本 JAR 的只读链接后执行：

```bash
sudo ln -sfn /opt/cloide/releases/RELEASE_ID/cloide-server-0.0.1-SNAPSHOT.jar /opt/cloide/server/cloide-server.jar
sudo systemctl daemon-reload
sudo systemctl enable --now cloide-server
sudo systemctl status cloide-server --no-pager
```

应用日志由 journald 管理。使用 `sudo journalctl -u cloide-server` 检查启动错误；不得提高生产日志等级以记录 WebSocket 内容。

## Nginx 合并与验证

1. 备份当前 Nginx 站点配置并验证备份可读。
2. 确认 Java 监听回环，网关对外仅开放所需的 API 与 WebSocket 入口。
3. 将模板的请求体上限和 location 块合并到对应 HTTPS 站点，按企业域名配置 TLS 证书。两个经中控鉴权的制品上传入口默认允许 128 MiB，其余 API 保留 16 MiB 上限。调整制品上限时须同步应用和构建节点设置。
4. 在目标主机执行 `nginx -t`，通过后由 `systemctl reload nginx` 重载。
5. 从公网验证 HTTPS、`/api/health`、`/api/ready`、`/ws/agent` 和 `/ws/monitor`；分别记录结果。

Nginx 默认访问日志不包含 WebSocket 帧正文。不要为排障记录终端消息体、Ticket、JWT 或 Agent Token。

## 数据库备份与发布

企业自托管实例可将 MySQL、密钥材料和不可变制品放入同一份加密备份，恢复至独立数据库和新目录后再进行节点对账。操作和验证命令见[中控加密备份与旁路恢复](../recovery/README.md)。

在执行会触发 Flyway 的首次启动或版本升级前：

1. 确认目标数据库仅为本次批准的生产库，且记录当前 Flyway 版本。
2. 使用目标环境已批准的备份工具创建逻辑备份，不在命令行或日志中暴露密码。
3. 验证备份文件存在、大小大于零且可读取；在开发日志中记录备份路径和校验结果，不记录凭据。
4. 再启动应用，由 Flyway 执行向前迁移；禁止修改或重置已执行的迁移历史。
5. 检查 `GET /api/health`、`GET /api/ready`、`systemctl status` 和 Flyway 日志后才宣布发布完成。

> 旧监控部署可继续使用 `deploy/server/backup.sh` 和 `restore.sh`。归档包含数据库及环境文件的明文，使用该部署专属的私有备份目录。启用了工程制品或文件型密钥的实例使用上方的中控加密备份与旁路恢复流程。

## 升级与回滚

升级前保留当前 JAR 路径和已验证的备份。生产配置在既有环境文件未指定制品目录时使用 `/var/lib/cloide/artifacts`，由现有的 cloide 服务用户创建。此前指定了其他制品目录的部署应保留原设置，或先迁移并校验实际制品。通过软链接切换 JAR 并重启服务。

```bash
sudo ln -sfn /opt/cloide/releases/NEW_RELEASE/cloide-server-0.0.1-SNAPSHOT.jar /opt/cloide/server/cloide-server.jar
sudo systemctl restart cloide-server
```

若新版本未通过健康检查，先停止进一步流量变更，再将软链接恢复到先前 JAR 并重启。已执行的 Flyway 向前迁移不能通过替换 JAR 自动回滚；数据库恢复必须在明确影响范围、确认备份可用并取得最终批准后单独执行。

## 验收记录边界

在装有 Python 3、Nginx、OpenSSL 和 GNU coreutils 的 Linux 主机，可从源码根运行 `python3 scripts/release/verify-linux-gateway.py`。该检查在随机回环端口加载模板、通过 HTTPS 校验静态路由、身份请求头及两个制品入口的大小上限。备份权限和失败清理使用模拟的 mysqldump 输出进行验收。自定义 Nginx 若将 Lua 模块安装在专有前缀，可通过 `CLOIDE_TEST_NGINX_LUA` 指定包含 `resty/core.lua` 的目录。企业公网证书、生产鉴权、中控持久数据及实际 Agent 链路另行验收。
