# Agent 笔记：后端与前端技术基线

状态：提议中

## 先说结论

项目从 Spring Boot 3.x 的模糊表述切到可执行的 Boot 4.1.2 + MyBatis-Plus 3.5.18
+ springdoc 3.2.0 组合，前端固定 React 19 + Base UI + TanStack + axios + zod +
ECharts。2026-10-29 已用最小工程实测通过；骨架建立时按本笔记的版本落地，
失败则回退 Boot 3.5.16 并记录 EOL 例外。

## 问题

原规范只写「Spring Boot 3.x」，没有锁定小版本和 ORM/文档依赖，导致：

- 骨架一落地就可能选到已停止 OSS 支持的 3.5 线（3.5.16 是 2026-06 后最后一个
  OSS 版本），安全补丁与依赖升级没有来源。
- `review-policy` 把 MyBatis、ECharts 当成「已引入」，但仓库没有 `pom.xml` /
  `package.json`，review 会以不存在的依赖为前提。
- 前端只列了部分库，表单校验、请求客户端、图表和图标没有统一选型，容易出现
  两套并存。

## 提案

### 正确做法

- 后端：Spring Boot 4.1.2、MyBatis-Plus 3.5.18（`mybatis-plus-spring-boot4-starter`）、
  springdoc-openapi 3.2.0、Flyway、Jakarta Validation；版本写进 `server/pom.xml`。
- 前端：React 19、Vite、Tailwind CSS v4、shadcn/ui（Base UI，`render`）、
  TanStack Router/Query/Form/Table、Zustand、axios（请求拦截与统一错误）、
  zod（校验）、ECharts（图表，按需懒加载）、lucide-react（图标）、sonner（toast）；
  版本写进 `web/package.json` 与 `pnpm-lock.yaml`。
- OpenAPI 统一使用 3.1.0：springdoc 3.2.0 默认输出 3.1.0；`docs/openapi/` 模板
  已同步，空值用类型数组表达，不使用 `nullable`。
- 升级依赖前先跑骨架的兼容性检查：上下文启动、MP CRUD、`/v3/api-docs`、
  华为云 SDK 类加载，并记录结果。

### 不要这样做

- 不要继续写「Spring Boot 3.x」这类无小版本的表述，也不要把 3.5.16 当作
  默认新项目基线；它只作为兼容性失败时的回退。
- 不要同时引入两套表单/图表/请求库；选型变化先改本笔记与骨架计划。
- 不要把 OpenAPI 3.1 的 `nullable` 写法带回模板，也不要让手写契约与
  springdoc 输出长期分叉。

## 考虑过的备选方案

### 备选方案一：Spring Boot 3.5.16

生态最稳、华为云示例最多，编译风险最低。但 3.5 线 OSS 支持已结束，新项目
从第一天就缺安全补丁；只在 Boot 4 组合实测失败时采用，并记录 EOL 例外与
缓解措施（依赖扫描、WAF、限制公网暴露）。

### 备选方案二：Spring Boot 4.0.x

比 4.1.2 更早一个 minor，兼容性更保守。但 MyBatis-Plus 3.5.18 的 boot4 starter
已按 Boot 4.1.0 构建，4.1.2 是当前稳定补丁；没有理由停在 4.0.x。

## 验收标准

- `server/pom.xml` 使用 Spring Boot 4.1.2、MyBatis-Plus 3.5.18、springdoc 3.2.0，
  `./mvnw test` 通过，应用上下文可启动。
- `web/package.json` 含本笔记列出的依赖，`pnpm typecheck`、`pnpm lint`、
  `pnpm test`、`pnpm build` 通过。
- `/v3/api-docs` 返回 200，`openapi` 为 `3.1.0`。
- 华为云 SDK 客户端能在不发起云上请求的前提下完成类加载与凭据对象构造。
- `docs/plans/20261029-规范修复与骨架准备.md` 的基线与实际 `pom.xml` /
  `package.json` 一致。

## 风险

- 华为云 SDK 与 Spring Boot 4（Jackson 3）在真实序列化场景可能与本次
  最小加载测试不同；骨架第一次接 MaaS/OBS 时需再验证一次。
- MyBatis-Plus 3.5.18 较新，若出现回归可回退 3.5.17，并在本笔记记录。
- 若 Boot 4 组合失败，按备选方案一整体回退，不混用 Boot 3 与 boot4 starter。
- OpenAPI 3.1 的部分旧工具链可能不兼容；前端类型生成以 `openapi-typescript`
  当前版本为准，失败时在骨架计划里记录替代方案。

## 验证

- `node .agents/skills/hwict-docs/scripts/check-docs-links.mjs`
- `node .agents/skills/hwict-notes/scripts/verify-agent-notes.mjs`
- `.artifacts/boot4-spike` 实测：3 个测试全绿，`/v3/api-docs` 输出 3.1.0

## 适用范围

- `.agents/skills/hwict-java/SKILL.md`
- `.agents/skills/hwict-react/SKILL.md`
- `docs/plans/20261029-规范修复与骨架准备.md`
- `docs/openapi/`
