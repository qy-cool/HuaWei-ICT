# 订单系统回归工程

项目可以独立复制到 Git 仓库。Java 21 与 Spring Boot 提供订单 HTTP API，PostgreSQL 保存订单，Redis 保存按订单版本分隔的缓存，RabbitMQ 接收已确认提交的订单事件。企业可以使用自行选择的前端框架或其他客户端调用 API。

## 启动和构建

1. 准备独立 PostgreSQL 数据库、Redis 和 RabbitMQ vhost。把 ORDERS_DB_URL、ORDERS_DB_USER、ORDERS_DB_PASSWORD、ORDERS_REDIS_HOST、ORDERS_MQ_HOST、ORDERS_MQ_USER、ORDERS_MQ_PASSWORD 写入服务环境。端口、Redis 口令和 vhost 可按 `api/src/main/resources/application.properties` 配置。
2. 从 api 目录运行 `mvn -B package`，启动 `java -jar target/orders-0.1.0.jar`。Flyway 使用该业务数据库运行迁移。默认回环监听 8080，开发时可按需修改 ORDERS_BIND 和 ORDERS_PORT。
3. 访问 `GET /api/orders` 查看订单列表，发送 `POST /api/orders` 和 JSON 请求体 `{"item":"测试商品"}` 创建订单。此回归项目用于隔离环境，企业接入自己的身份与业务角色后再向业务用户开放。
4. 保留测试前基准提交、测试报告、JAR 摘要及发布前后新增订单 ID。节点运行 Maven 的命令由管理员登记的工具链决定。清单用于导入审阅，执行前须绑定节点和密钥。

## 业务核验

创建订单后查看详情两次，检查 Redis 中同一订单 ID 和版本号的缓存。取消一笔待发货订单，再次取消应得到相同版本。已发货订单取消返回 409。订单与事件在同一事务中写入，消息到达 RabbitMQ 并获确认后才标记为已发送；消费者用事件 ID 去重。停止消息通道时检查待发记录，恢复后核对 order_event_receipts 的唯一条数。

应用回滚时核对发布之前和之后创建的订单，记录 ID、状态和版本。结构迁移须同时保留旧应用可读取的字段。恢复测试数据时，使用新的数据库实例并重新绑定只用于验收的业务进程。

## 复跑签名 Java 发布与数据回退

使用启用 systemd 用户管理器的独立 Linux 验收机。准备 Java 21、Python 3.9+、OpenSSL、Docker、setpriv 及本项目构建的订单 JAR。先按 `verify_runtime.py` 的 `IMAGES` 准备三个固定摘要镜像。从订单项目根目录运行以下命令，最后一个参数指定平台仓库中的发布执行器。

```sh
sudo python3 -B verify_java_release.py \
  api/target/orders-0.1.0.jar /path/to/platform/deploy/release/node.py
```

管理员权限用于临时账户、用户服务管理器及专用容器的创建和清理。发布执行器与两个 Spring Boot 单元以独立低权限身份运行。脚本使用本机 HTTPS 验收接口提供固定发布任务，对订单 JAR 和两个测试变体签名，实际执行候选启动、切换、探针失败恢复及显式回滚，随后核对数据库、缓存与消息回执。所有监听端口仅绑定回环，测试数据使用临时数据库和卷。
