# 独立中间件实例、备份与恢复

在目标 Linux 节点运行 `instance.py`。一个实例占用一个 Compose 项目、独立网络和持久卷。以不同目录在不同服务器初始化，即可独立部署 PostgreSQL、Redis 和 RabbitMQ。工具要求 Python 3.11+、Docker Engine 和 Compose v2，操作者具有本节点的 Docker 权限。单个实例目录中的 `compose.json` 为可审查、版本固定的 Compose 清单，`instance.json` 保存 UUID、模板和时间。

## 初始化和检查

```bash
python3 instance.py init /srv/cloide/orders-pg postgres --address 10.0.1.10 --port 5432
python3 instance.py up /srv/cloide/orders-pg
python3 instance.py check /srv/cloide/orders-pg
python3 instance.py backup /srv/cloide/orders-pg /mnt/backup/orders-pg-001
python3 instance.py restore-new /srv/cloide/orders-pg-verify /mnt/backup/orders-pg-001
python3 instance.py stop /srv/cloide/orders-pg-verify
```

实例和备份的父目录需要预先存在。初始化、备份和恢复的目标各自采用新的空路径。重复 `up` 核对并启动同一项目，`check` 执行应用协议检查，`stop` 保留卷。安装前可以先检查生成的 `compose.json`、监听端口和依赖版本。未指定业务监听地址时，业务端口仅出现在此实例的 Docker 私网中。RabbitMQ 管理 API 始终在宿主机回环使用动态端口。跨机应使用仅面向应用节点的私网、访问控制和加密连接；在应用节点实测 DNS、业务身份与读写。示例未配置原生数据库 TLS。

实例目录必须由专属运维账户保管。工具首次创建 `admin` 和 `app` 两个权限 0600 的凭据文件，数据库连接使用角色 `orders_app`，业务库和 RabbitMQ vhost 均为 `orders`。由企业密钥系统向应用分配业务凭据。恢复生成新密码，切换前同步应用的密钥引用。每一份备份目录单独设置访问控制、加密副本、保留期及访问审计。

默认版本为 PostgreSQL 18.6、Redis 8.10.3 和 RabbitMQ 4.3.7-management，安装时可用 `--image 仓库@sha256:摘要` 固定已在企业核验的官方镜像。对 `restore-new` 传同一选择。恢复工具还检查实际镜像 ID，需要在目标节点预装原镜像，因此跨 CPU 架构恢复须另行演练。升级前记录实际版本、剩余磁盘、备份索引和一次新实例恢复结果，检查对应上游版本的格式兼容性。支持的镜像来源见[PostgreSQL](https://github.com/docker-library/official-images/blob/master/library/postgres)、[Redis](https://github.com/docker-library/official-images/blob/master/library/redis)和[RabbitMQ](https://github.com/docker-library/official-images/blob/master/library/rabbitmq)。

## 各服务的数据语义

| 模板 | 备份与目标 | 业务验收 |
|---|---|---|
| `postgres` | 逻辑备份覆盖业务库 `orders`，按模板重建应用角色。集群级物理备份与连续 WAL 归档使用下方可选流程。 | 逻辑恢复核对主键、CHECK 约束、订单值和应用角色的事务写入。物理恢复核对命名恢复点、重放 LSN 及恢复点前后的业务事务。[pg_dump](https://www.postgresql.org/docs/current/app-pgdump.html) |
| `redis-cache` | 记录模板及冷启动策略。重建为空缓存，128 MiB 上限，allkeys-lru 淘汰。 | 业务读取权威数据源后回填缓存，检查返回值和 TTL。演练脚本用固定订单回填，真实应用仍需测试回源流量。 |
| `redis-persistent` | 上限 128 MiB，noeviction，AOF everysec。使用 `redis-cli --rdb` 生成快照；新实例先加载 RDB，启用 AOF，等待重写成功后重启。 | 核对键值、剩余 TTL 和 AOF 再启动。异机备份的数据恢复点是实际快照时点，另记录备份后新增与过期键的业务影响。[Redis 持久化](https://redis.io/docs/latest/operate/oss_and_stack/management/persistence/) |
| `rabbitmq` | 包含独立 vhost、业务角色、直接交换机、消费者队列、死信交换机/队列及绑定。导出单一 vhost 的 definitions，恢复到独立实例。 | 导入后核对拓扑、持久性、死信参数。源队列中未消费的消息不会随 definitions 恢复。另用业务 ID 从 outbox 重放，检查路由、重投、确认和业务去重。演练覆盖 HTTP 管理发布/取消息与测试 ID 去重，AMQP publisher confirms 和业务消费者事务需在应用层验证。[RabbitMQ 备份](https://www.rabbitmq.com/docs/backup) |

## 恢复证据与后续切换

备份结束后生成 `manifest.json`，记录源实例、镜像、时间、大小及 SHA-256。通过受信的备份存储和来源验证接收清单，自校验摘要只能发现内容损坏。恢复先校验清单和内容，另建随机 UUID、凭据、卷和网络，再写 `restore.json`。成功状态 `service_checked` 表示实例的服务探针通过。应用负责人核验业务后单独记录结果，经授权切换连接。工具不会自动改动源实例或生产路由。恢复中断时保留新目录和卷，正常异常处理尝试停止目标；进程突然退出后应查明已创建的目标资源，再以另一空目录恢复。

`smoke.py` 为稳定版本提供可重复演练。每次从空源实例准备样本，生成备份后停止源服务，将备份恢复到第二个实例并进行业务核对。脚本最后用本次生成的两个 Compose UUID 清理容器、卷和网络并按标签复查，成功时输出含恢复时长、摘要、证据及清理结果的 JSON。

```bash
python3 smoke.py postgres
python3 smoke.py redis-cache
python3 smoke.py redis-persistent
python3 smoke.py rabbitmq
```

## PostgreSQL 连续归档与命名恢复点

Linux 节点管理员为实例准备独立的备份文件系统。首次启用会重建该模板的数据库容器，应选择维护窗口并核对源实例。归档目录须为新的专属路径，父目录应存在。覆盖数据库或备份介质的故障演练需要在实际独立故障域进行。生产副本须同时保管 cluster.json、各基础备份、恢复点清单和持续增长的 WAL 目录，对备份介质实施加密及访问控制。只有业务负责人完成恢复验收后才能切换业务连接。

```bash
python3 postgres_pitr.py enable /srv/cloide/orders-pg /mnt/backup/orders-physical
python3 postgres_pitr.py base /srv/cloide/orders-pg
python3 postgres_pitr.py point /srv/cloide/orders-pg before_migration
python3 postgres_pitr.py restore-new /srv/cloide/orders-review /mnt/backup/orders-physical BASE_ID POINT_NAME
```

`base` 用 `pg_basebackup` 生成包含 WAL 和 SHA-256 备份清单的完整物理副本，运行 `pg_verifybackup` 后输出基础备份 ID。`point` 会返回带唯一后缀的完整名称和 LSN，并等待该段 WAL 成功归档。选择同一集群、同一时间线、基础备份完成之后的命名点。恢复时传入实际 ID 及完整名称，保留已有源实例和目标路径。该版本支持模板默认单表空间布局、同版本同架构镜像及命名点恢复。留意 `pg_stat_archiver.failed_count`、最后成功归档时间、源 `pg_wal` 与备份盘的剩余容量。归档失败会使源端 WAL 积压，保留期需涵盖所需基础备份至目标点的完整 WAL 链。 [PostgreSQL 连续归档与恢复](https://www.postgresql.org/docs/18/continuous-archiving.html) · [基础备份](https://www.postgresql.org/docs/18/app-pgbasebackup.html)

恢复工具在新实例校验基础备份清单和目标 WAL 摘要，通过只读挂载回放，核对系统标识与实际重放 LSN。模板管理员及业务角色更换为目标实例的新密码，源集群的其他角色、授权和数据仍保留其恢复点状态，企业需单独核对和轮换。目标不发布宿主机端口；完成业务核查后再绑定网络和凭据。保留失败目录查证原因，以另一新目录重试。可运行 `python3 pitr_smoke.py --image postgres@sha256:...` 验证真实恢复点前后的订单及目标的独立写入。[PostgreSQL 恢复目标](https://www.postgresql.org/docs/18/recovery-target-settings.html)

本工具目前由节点管理员运行。自动调度、跨机 S3 归档、审计索引、容量告警与多副本故障切换属于后续工作。[独立实例验收记录](verification.md)
