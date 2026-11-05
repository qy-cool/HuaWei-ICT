---
name: hwict-java
description: HuaWei-ICT Java 后端编码规范。当在 HuaWei-ICT 项目 server/ 目录下编写 Java/Spring Boot 代码时自动加载。触发场景：编写 Controller、Service、模块目录设计、后端安全与配置、后端测试。
when_to_use: 编写 Controller / Service / 后端模块目录 / 后端测试 时
user-invocable: true
---

# HuaWei-ICT Java 后端规范

本项目 Java 后端遵循《阿里巴巴 Java 开发手册》、Spring Boot 常规实践和项目需求文档中的约束。

后端目标基线（版本以 `server/pom.xml` 为准）：Java 21、Spring Boot 4.1.2、
MyBatis-Plus 3.5.18（`mybatis-plus-spring-boot4-starter`）、springdoc-openapi 3.2.x、
Flyway、Jakarta Validation；升级依赖前先按骨架计划的兼容性检查执行并记录结果。

迁移期例外（2026-10-29）：`server/` 由 cloide-shell 迁入，当前实际为
Spring Boot 3.4.13 / MyBatis-Plus 3.5.12 / springdoc 2.8.9 / OpenAPI 3.0.3，
本轮不做升级；实际版本始终以 `server/pom.xml` 为准。

## 1. 命名规范

- 类名使用 UpperCamelCase，例如 `ServerController`、`JwtTokenService`
- 方法名、参数名、局部变量名使用 lowerCamelCase
- 常量使用 UPPER_SNAKE_CASE
- 包名使用小写单词，主包为 `cloide.server`
- **布尔变量不使用 `is` 前缀**，例如使用 `enabled`，不使用 `isEnabled`

## 2. 注释规范

- 注释解释"为什么这样实现"与关键约束（踩坑原因、设计决策、安全边界），不重复代码字面含义
- 不要求为每个注解、类、方法添加注释；注解参数、字段命名或 Javadoc/@Schema 已能表达的不再用行注释复述
- 复杂业务逻辑、非显然的设计决策、框架陷阱规避应注释说明
- 配置项与字段的条件语义（null 值含义、单位、默认值行为）应注释说明
- 注释必须与代码保持同步；修改代码行为时必须同步更新相关注释

## 3. 目录规范

Java 后端工程目录固定为 `server/`。

```text
server/src/main/java/com/hwict/server/
├── config/
├── common/
├── security/
├── module/
└── client/          # 华为云服务（MaaS 大模型、OBS 等）SDK 封装
```

业务模块放在 `module/` 下，每个模块推荐结构：

```text
模块名/
├── controller/
├── service/
├── mapper/
├── entity/
├── dto/
└── vo/
```

## 4. Controller 规范

- Controller 只处理请求参数、认证上下文和响应封装
- 业务规则放在 Service，不写在 Controller 中
- 请求参数使用 DTO，响应对象使用 VO
- 所有 REST API 使用统一响应结构（`ApiResponse<T>`）

## 5. Service 规范

- Service 负责业务规则、事务、跨表协作和权限边界
- 不用异常做正常业务流程控制
- 捕获异常后必须处理或继续向上抛出统一业务异常
- 涉及数据库写操作时明确事务边界

## 6. 数据库规范

- 数据库设计、SQL 与 Flyway 迁移规范见 `hwict-mysql` skill
- 用户密码只保存 BCrypt 哈希
- 日志不得输出密码、Token、API Key、云凭据等敏感信息

## 7. 安全规范

- JWT 密钥、华为云 AK/SK、MaaS API Key 等凭据只从配置或环境变量读取，不硬编码、不入库
- 对华为云服务（MaaS、RDS、Redis、OBS 等）的调用统一封装在 `client/` 层，失败显式处理，不在业务代码中散落 SDK 调用
- 管理类接口必须校验角色/权限
- 涉及 AI 生成内容对外展示时，注意提示词注入与敏感内容过滤，用户输入不直接拼接进系统提示词

## 8. 测试规范

- 单元测试使用 JUnit 5
- Service 隔离测试可使用 Mockito
- 新增核心业务规则时应补充对应测试
- 测试稳定性细则见 `hwict-tests` skill
- 提交前至少执行 Maven 测试命令（`./mvnw test`）

## 9. 文档同步

文档同步要求（OpenAPI 契约、协议文档、开发日志）见 `hwict-docs`
skill 的工作流程。
