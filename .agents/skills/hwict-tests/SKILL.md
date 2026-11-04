---
name: hwict-tests
description: HuaWei-ICT 稳定测试规范。当在本仓库创建、修改或审查测试代码或测试基础设施时自动加载。触发场景：编写 JUnit/Vitest 测试、处理并发/定时器/轮询/子进程测试、排查偶发失败或挂起的测试。
when_to_use: 编写 / 修改 / 审查测试代码 时
user-invocable: true
---

# HuaWei-ICT 稳定测试规范

本 Skill 的目标是让坏测试在本地带诊断地失败，而不是偶发通过、挂起或在
别人机器上才失败。适用于后端 Java/JUnit 5 与前端 Vitest 测试。

> 本规范改编自团队既有项目（Lithe-IDEA / cloide-shell）的稳定测试 skill，
> 按本项目技术栈重写了平台参考。

## 1. 工具链边界

- 后端：Java 21 + Maven + JUnit 5 + Mockito（`./mvnw test`），隔离 MySQL
  集成测试按 `references/java-spring.md` 的门控约定。
- 前端：Vitest（`pnpm test` / `pnpm test:watch`），不引入真实浏览器或
  真实后端。
- 本地与 CI（GitHub Actions）保持同一大版本工具链（Java 21 / Node 24）；
  本地能过而 CI 失败时，先比对工具链版本再怀疑测试本身。

## 2. 平台参考

- Java / Spring 测试读 [references/java-spring.md](references/java-spring.md)
- React / Vitest 测试读 [references/vitest-react.md](references/vitest-react.md)
- 共享契约或前后端行为变更时两篇都要读。

## 3. 硬性不变量

- **每个等待都有本地截止时间**：JUnit 用 `assertTimeoutPreemptively` 或
  Awaitility 的 `atMost()`；Vitest 用注入的 fake timers 或带超时的轮询
  工具。测试步骤超时绝不是由构建命令或 CI 的整体超时来兜底。
- **不用真实 sleep 同步状态**：不写 `Thread.sleep(n)` 等"给后台一点时间"；
  注入时钟 / 调度器 / 事件 / CompletableFuture / 可控替身，等可观察
  事件而不是等时间。
- **不为编译通过把阻塞等待藏进后台任务**：不把阻塞等待挪进 detached
  线程 / 后台 Executor / fire-and-forget Promise 来让异步测试编译通过；
  禁止阻塞事件循环或 UI 线程。
- **每个资源有唯一 owner 和清理路径**：线程、定时器、Executor、WebSocket
  连接、Mockito stub、Vitest 定时器/监听器/订阅，在断言失败时也要释放
  （`@AfterEach` / `defer` / `finally` / `unmockAll()` / `vi.restoreAllMocks()`）。
- **并发测试要描述并控制事件顺序**：启动 → 到达同步点 → 释放或取消 →
  终止，四步都要有断言或显式等待。
- **轮询是最后手段**：必须有单调截止时间和超时诊断信息，轮询可观察边界
  （返回值、状态字段、日志标记）而不是私有内部状态。
- **单元测试不依赖真实网络、真实数据库实例、机器速度、个人路径或墙钟
  时间**。离不开外部依赖的放进显式标注的集成测试（规划中的隔离 MySQL
  集成测试 `*MySqlValidationIT` 即属此类）。
- **断言可观察行为**：不为了测试削弱生产行为、不只为测试暴露私有状态、
  不删除测试来通过验证。

## 4. 必要工作流

1. 先读被改行为的实现与邻近测试，列出测试将等待的每一个异步边界和资源。
2. 先选定确定性同步方式，再写断言；竞态回归要在测试名或替身命名里写出
   预期事件序列。
3. 迭代时先跑聚焦测试：

   ```bash
   ./mvnw test -Dtest='XxxTests#method'        # 后端
   pnpm test -- src/path/file.spec.ts           # 前端
   ```

4. 交付前跑受影响范围的完整验证（见 `hwict-dev` 验证矩阵），并在
   总结中如实报告：跑了什么、什么没跑、本机跑不了什么。

## 5. 豁免

确实无法避免阻塞或真实时间的原生同步边界（如等待本地 PTY / 子进程），
必须：隔离在专用线程、加本地短超时、保证清理，并在相关行上方注明：

```text
test-stability: allow(<rule-id>) reason: <为什么确定性同步在此处不可行>
```

reason 必须说明架构约束，不能复述代码。新增豁免必须在交付说明中明确
提出，不得为了让检查通过而添加。
