# cloide-shell Server 升级与回滚手册（MVP-8）

**适用范围**：Java 后端版本升级、失败回滚与决策边界。
**核心原则**：**JAR 可回滚，数据库不可回滚**（Flyway 迁移只进不退）；升级前数据库备份是硬性前置。

---

## 一、升级前置（不可跳过）

1. **数据库备份必须先行**：执行《备份与恢复手册》的 `backup.sh`，确认备份文件非空且 `gzip -t` 通过，并**异地留存一份**。
2. **确认密钥可用**：`/etc/cloide/server.env` 存在且 0600；若密钥丢失，升级后旧 SSH 凭据密文将无法解密（见《备份与恢复手册》§密钥）。
3. 保留最近 N 个 RELEASE_ID（建议 ≥2），发布目录只读（`root` 属主）。

## 二、标准升级流程

```bash
# 1) 备份（硬性前置）
sudo bash /opt/cloide/deploy/backup.sh --dir /var/backups/cloide

# 2) 构建并上传新版本
#    开发机: cd server && ./mvnw clean package -DskipTests
sudo install -d -o root -g root /opt/cloide/releases/RELEASE_ID_NEW
#    上传 cloide-server-0.0.1-SNAPSHOT.jar 到该目录

# 3) 切换软链并重启
sudo ln -sfn /opt/cloide/releases/RELEASE_ID_NEW/cloide-server-0.0.1-SNAPSHOT.jar \
             /opt/cloide/server/cloide-server.jar
sudo systemctl restart cloide-server

# 4) 验证
sudo journalctl -u cloide-server -n 50 --no-pager        # 启动无 ERROR
curl -s http://127.0.0.1:18080/api/health
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:18080/api/ready   # 200
# Flyway 迁移：journalctl 中出现 "Successfully applied N migrations" 视为新迁移已应用
# 按《部署安装手册》§六 验收清单抽查关键项（verify-alert-ws / verify-outbox）
```

升级期间行为：`systemctl restart` 约 5-30 秒（JVM 启动 + Flyway 迁移），期间 health 短暂不可达属预期；Agent 会按指数退避自动重连（Go Agent 内置重连）。

## 三、回滚边界与决策矩阵

| 场景 | JAR 回滚 | 数据库回滚 | 结论 |
|---|---|---|---|
| 新版本启动失败（配置/代码错误） | ✅ 软链切回旧版 | 不需要 | 纯代码回滚，数据未受影响 |
| Flyway 迁移成功但业务异常 | ✅ 软链切回旧版 | ❌ 不可自动回滚 | **必须从备份恢复数据库**（新迁移已写入 `flyway_schema_history`，旧 JAR 无法识别新 schema） |
| 迁移中途失败 | 视情况 | 视情况 | **立即停服评估**：备份完整则可整库恢复；否则联系专业 DBA |
| 密钥/配置损坏 | ✅ 恢复 server.env | 不需要 | 从备份恢复 `server.env`（见《备份与恢复手册》） |

**数据库回滚的唯一途径是备份恢复**：
```bash
# 停止应用 -> 恢复数据库 -> 启动应用（详细步骤见《备份与恢复手册》§恢复）
sudo systemctl stop cloide-server
sudo bash /opt/cloide/deploy/restore.sh --backup /var/backups/cloide/xxx.tar.gz
sudo systemctl start cloide-server
```

> ⚠️ Flyway 语义：迁移脚本一旦在任意环境执行过即不可修改（校验和变化会导致 `validate` 失败）；修复迁移必须新增 V 版本脚本，禁止编辑历史迁移。

## 四、RabbitMQ 升级注意（MVP-10 依赖）

1. **版本匹配**：RabbitMQ 4.3 要求 Erlang 27.x；**Erlang 29 与 RabbitMQ 4.3 不兼容**（horus 机制启动失败，本机验收实测）。升级前核对官方版本矩阵。
2. **拓扑幂等**：Exchange/Queue/DLX/DLQ 由 `RabbitMqTopologyConfig` 声明式创建，broker 重启/升级后自动重建，无需手工声明；升级 broker 不影响后端连接（spring-rabbit 自动重连）。
3. **停机影响**：Broker 停机期间指标照常落库，outbox 事件退避堆积；Broker 恢复后**自动补发**（MVP-10 三阶段验收证据，见 旧项目开发日志）。`/api/ready` 返回 50301（存活但未就绪），应用不退出。
4. 升级 broker 前建议 `rabbitmqctl list_queues` 记录积压基线，升级后对比确认无消息丢失。
