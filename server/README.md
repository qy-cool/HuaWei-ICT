# cloide-shell server

Java 21 + Spring Boot 3.4.13 后端：认证/RBAC、服务器管理、指标、告警、SSH 终端中继、
可靠消息（Outbox/RabbitMQ）与 AI 诊断。

## 目录

```text
src/main/java/cloide/server/
├── common/ config/ security/ client/
├── module/{admin,ai,alert,auth,command,metrics,server,system,terminal}/
├── ssh/ websocket/ scheduler/
└── CloideShellApplication.java
src/main/resources/
├── application.yml / application-local.yml / application-prod.yml
├── db/migration/          # Flyway V1-V37
└── mapper/                # MyBatis XML
```

## 本地运行

```bash
./mvnw test                 # 单元测试（H2，不需要外部依赖）
./mvnw spring-boot:run      # 需要 MySQL + RabbitMQ；见下
```

> 确认 `JAVA_HOME` 指向 JDK 21；JDK 25 下 Mockito inline mock 会失败
> （`Mockito cannot mock this class`）。默认 `java` 不是 21 时，显式
> `JAVA_HOME=<JDK21 路径> ./mvnw test`。

启动前必须提供（否则 `AppProperties` fail-fast）：

- `JWT_SECRET`：≥32 字节 Base64
- `AES_GCM_KEY`：恰好 32 字节 Base64
- `DB_HOST/DB_PORT/DB_NAME/DB_USER/DB_PASSWORD`
- RabbitMQ：`SPRING_RABBITMQ_*`；仅做单机调试时可 `OUTBOX_ENABLED=false`

本地依赖可用根目录 `docker-compose.yml` 拉起 MySQL/RabbitMQ/Redis。

## 版本

迁移期保持 Spring Boot 3.4.13 / MyBatis-Plus 3.5.12 / springdoc 2.8.9 / Spring AI 1.0.0；
仓库目标基线（Boot 4.1.2 / MP 3.5.18 / springdoc 3.2.0）升级另议。
实际版本以 `pom.xml` 为准。

## 文档

- 接口契约：`docs/openapi/`
- WS/RabbitMQ 协议：`docs/protocol/`
- 架构与数据库：`docs/tech-summary/`
- 部署运维：`docs/ops-manual/`
