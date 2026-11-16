# 中控加密备份与旁路恢复

适用于包含 `project_artifacts` 的 MySQL 8.4 中控、当前文件系统制品后端，以及使用环境文件部署的实例。旧版单库安装脚本仍见 [server 部署手册](../server/README.md)。本流程将中控数据库、当前环境材料、文件型密钥和数据库实际引用的不可变制品写进同一份离线快照。备份成功与恢复对账分别留证。

## 备份

需要 Python 3.11+、Python 包 `cryptography`、MySQL 8.4 的 `mysql` 和 `mysqldump`。Linux 建议使用权限 0700 的专有目录、0600 的配置与密钥文件以及加密临时盘。Windows 为备份和临时目录设置只允许当前服务账户和管理员访问的 NTFS ACL。操作人提供专用 MySQL 客户端 option file，其中的密码通过文件传递。程序禁用个人 MySQL login path。企业通过备份存储和外部密钥系统管理保留期、异地副本及访问审计。

```ini
[client]
host=127.0.0.1
port=3306
user=cloide_backup
password=<由企业密钥系统注入>
```

```bash
python3 control_backup.py keygen /secure/offline/control-backup.key
python3 control_backup.py backup --mysql-options /secure/backup-client.cnf \
  --database cloide --environment /etc/cloide/server.env \
  --artifacts /var/lib/cloide/artifacts --scratch /mnt/encrypted-scratch \
  --archive /mnt/backup/cloide-001 --key-file /secure/offline/control-backup.key
```

备份密钥为独立的 32 字节二进制文件。通过不同的控制渠道保管和演练恢复这把密钥；丢失后无法解密。数据库连接文件需要目标库的读取权限及枚举表、触发器、例程、事件的可见性。所有表必须为 InnoDB 普通表。在备份窗口暂停 DDL、制品清理和密钥轮换。产品数据继续写入，由 `mysqldump --single-transaction` 获取事务快照。程序在 dump 前后比较节点 Token 生命周期指纹、节点分配、制品索引和环境材料；发生变化时返回失败，重新选择新的备份目录重试。该版本拒绝可见的自定义视图、触发器、事件和例程。

对 `CLOIDE_GITHUB_PRIVATE_KEY_FILE` 指向的文件自动备份。运行备份的主机看不到容器内路径时用 `--key-material github-app=/实际挂载路径` 指明来源。其他外部密钥用可重复参数 `--key-material 名称=文件路径` 逐项指定。外部 Vault、KMS、对象存储、Redis 会话、GitHub、工作区卷和业务数据库有各自的恢复流程。独立中间件实例见[中间件恢复手册](../middleware/README.md)。

快照目录 `manifest.enc` 在最后写入；每份制品、SQL、环境文件及附加密钥分别使用 AES-256-GCM，新随机 nonce 和绑定快照 UUID 与对象名的 AAD。备份端逐对象比对数据库记录的大小及 SHA-256，并实际解密 SSH、AI 供应商和管理员初始化密文，验证所给环境材料属于当前数据库。加密前的数据库 dump 会暂存于选定临时盘；正常返回后删除，主机非正常关机后由管理员检查残留。总可用空间至少覆盖输入数据库 dump、归档及恢复出的明文制品。

## 恢复及对账

```bash
python3 control_backup.py restore-new --mysql-options /secure/recovery-admin.cnf \
  --archive /mnt/backup/cloide-001 --key-file /secure/offline/control-backup.key \
  --new-directory /srv/cloide/restored-001 \
  --node-token-samples /secure/recovery-node-samples.json
```

目标 MySQL 保持与生产数据写入隔离。恢复专用 DBA 需要创建新数据库、表、索引和导入行的权限。程序先认证快照及每个对象，再生成新数据库名 `cloide_restore_<UUID>` 并以 `CREATE DATABASE` 创建；冲突会中断。新目录已存在也会中断。可选的 `--node-token-samples` 是仅操作者可读的 JSON，结构为 `{"节点数字ID":"该测试节点当前持有的Token"}`。它用于核验已登记启用的当前 Token 与数据库 SHA-256，并不会将样本写入归档或报告。节点令牌轮换、撤销或项目变更晚于快照时，需要根据受信的外部记录逐项重放。

| 产物 | 用途 |
|---|---|
| `database-name` | 标识本次创建的唯一恢复库，可用于检查失败现场和后续清理 |
| `artifacts/` | 恢复后的内容寻址目录，布局与 `CLOIDE_ARTIFACTS_DIRECTORY` 一致 |
| `key-material/` 与 `source-env.material` | 原始受保护材料。后者仅作离线核对，包含旧生产连接参数 |
| `recovery.env` | 保留 AES/JWT 和适用的节点注册密钥，指向新目录和库名，仅绑定回环，开启项目隔离与 `ENGINEERING_RECOVERY_MODE`。DB 主机、端口、应用账户和密码预留为空，部署者填写新库的受限身份 |
| `reconciliation.json` | 记录快照、目标库、节点及制品数量、凭据信封解密数量、附加密钥数量与节点 Token 抽样结果 |

目标端逐项对账节点登记、凭据状态、节点对项目的分配、制品元数据和实际对象摘要。若 SQL 导入、抽样或对账失败，新库会留下现场，两种状态可通过 `database-name` 和 `reconciliation.json` 区分。重试使用另一新目录。脚本不会启动中控。DB 以外的 RabbitMQ、Redis、GitHub、模型请求和工作区入口需要在恢复网络中单独隔离。恢复阶段仅允许恢复管理员访问，核对节点连接、实际应用版本、未完成作业与发布回执、缓存中的撤权记录和备份后产生的变更。企业确认后再恢复外部连接与派发。

## 可重复的验收

```bash
python3 smoke.py --mysql-bin /opt/mysql-8.4/bin --scratch /mnt/encrypted-scratch
```

演练用新数据目录、随机回环端口、初始随机管理员密码及合成凭据启动临时 MySQL。源码检出使用仓库迁移 SQL，解压的 Linux 发行包自动读取自带 JAR 内的迁移，也可用 `--server-jar PATH` 指定候选包。演练逐个执行迁移，写入带加密 SSH 凭据的节点、项目分配和不可变制品。破坏加密制品的一字节后必须在创建数据库前被拒绝。随后从原快照验证独立数据库、环境材料、文件型密钥、Token 抽样、制品字节与暂停派发设置，关闭子进程并清理临时目录。这项演练单独覆盖 SQL 和备份工具；Flyway 管理升级和真实 Agent 对账另由中控集成测试验收。

2026-11-12 在 Windows x86_64、MySQL 8.4.9 上实际执行 V1–V50 共 50 份迁移 SQL。最后一轮快照 `6bb6bf95-8d5e-4776-823f-37e74cd18991` 的节点抽样、一个 AES 信封、一份独立文件型密钥和 1,350,000 字节的制品均通过恢复校验。制品索引校验包含仓库、提交、作业与构建节点。制品篡改被拒时数据库集合保持原样。同轮备份、损坏校验及有效恢复合计 3.23 秒，临时 MySQL 进程及文件已清理。正式实例的数据量、制品量、外部密钥系统和代理重连需要另行验收。

同日使用 `--server-jar` 改从 V50 发行候选 JAR 内取出全部 50 份 SQL，在独立 MySQL 8.4.9 实例完成相同的数据、密钥及损坏校验。快照 `1c3dd4db-30bc-45ad-8640-ee272edc2b09` 的备份及恢复耗时 3.31 秒，演练结束已移除临时数据库与文件。
