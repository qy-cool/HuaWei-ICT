---
name: hwict-react
description: HuaWei-ICT React 前端编码规范。当在 web/ 目录下编写 React/TypeScript 前端代码时自动加载。触发场景：编写组件与页面、路由、TanStack Query 数据获取、Zustand 状态、shadcn/ui 组合、请求封装、前端目录组织。
when_to_use: 编写 React 组件 / 路由 / 数据获取 / 状态管理 / 前端目录组织 时
user-invocable: true
---

# HuaWei-ICT React 前端规范

> 结构与团队既有项目 logistics-manager 的协作规范同源，已按本仓库技术栈与
> 比赛要求适配；具体配置以第 7 节基线清单为准。

## 1. 技术与目录

- 技术栈：React 19 + TypeScript + Vite + Tailwind CSS v4 + shadcn/ui（Base UI）
  + TanStack Router / Query / Form / Table + Zustand + axios + zod + ECharts +
  lucide-react + sonner；包管理器使用 pnpm，版本以 `web/package.json` 与
  lockfile 为准。
- 文件系统路由位于 `src/routes`；路由专属组件和逻辑放相邻的 `-components`、
  `-lib` 目录（如 `src/routes/analysis/-components/`），共用组件和逻辑放
  `src/components`、`src/lib` 或 `src/hooks`。
- `src/routeTree.gen.ts` 由 TanStack Router 插件生成，**不要手工编辑**；
  新增路由后运行 `pnpm dev` 或 `pnpm build` 更新路由树。
- Router 实例位于 `src/main.tsx`；QueryClient 位于
  `src/lib/query-client.ts`，通过 Provider 与 Router context 共享，避免
  重复创建。
- 类型定义放 `src/types/<module>.ts`，与 `docs/openapi/` 契约对齐；
  TanStack Query 的 queries / mutations 放 `src/queries/<module>.ts`，
  按模块组织，不散落在页面或组件中。

```text
web/src/
├── routes/            # 文件系统路由（页面），就近 -components/ -lib/
├── components/        # 跨页面共用组件（ui/ 为 shadcn 生成件，少改内部）
├── lib/               # query-client、请求封装、纯工具
├── hooks/             # 共用组合式函数
├── queries/           # TanStack Query 的 queries/mutations（按模块）
├── stores/            # Zustand store
├── types/             # 类型定义（按模块，对齐 OpenAPI）
├── styles.css         # 全局样式与设计 token（唯一入口）
└── main.tsx
```

## 2. 代码编写偏好

- 有代码洁癖：文件必须按职责放在明确、合适的位置；新增文件前先确认所属
  模块与目录。
- 共享客户端状态优先使用 Zustand，而不是 React Context。
- 代码只看结果，不写来源、不问过程：不在 README/文案里写"此文件来自某处"、
  不在 UI 功能附近添加"实现了 XX 功能"式的说明——新功能和现有功能是一个
  整体，避免浓厚的"AI 味"。

## 3. 界面规范

- 优先组合已有 shadcn/ui 组件，避免无必要地修改 `src/components/ui`
  内部实现（组件使用细则见 `hwict-shadcn`）。
- 优先使用语义化 HTML 标签，不要一律用 `div` 铺结构：页面骨架用
  `header`/`nav`/`main`/`aside`/`footer`，内容块用 `section`/`article`，
  列表用 `ul`/`ol`/`li`，键值信息用 `dl`/`dt`/`dd`。
- 可交互元素必须是真正的 `button`/`a`，不给 `div`/`span` 挂 `onClick`；
  需要把组件渲染成语义标签时用 Base UI 的 `render` 属性；改成 `a`/`Link`
  时必须同时传 `nativeButton={false}`，例如
  `<Button nativeButton={false} render={<Link to="/analysis" />}>查看</Button>`；
  Radix 的 `asChild` 不适用于本项目。
- 标题使用 `h1`–`h6` 严格按层级递进，不跳级；仅调视觉大小时用 Tailwind
  字号类，不借标题标签凑样式。
- 全局样式与设计 token 只在 `src/styles.css`；用语义颜色和 `cn()` 合并
  类名，不创建重复的全局样式入口。
- 界面文案默认使用中文；站内跳转使用 TanStack Router 的 `Link` 或导航 API。
- 表单控件需要可访问名称；每个数据视图处理**加载、错误、空**三种状态；
  长文本不能撑破页面，表格在自身容器内横向滚动。

## 4. 请求封装

- 对后端的 HTTP 调用统一走 `src/lib/` 中的请求封装（拦截器附加 JWT、统一
  错误处理），业务侧通过 `src/queries/` 的 hooks 使用，不在组件里裸写
  fetch。
- 接口路径与结构以 `docs/openapi/` 契约为唯一事实源，字段变更先改契约、
  再同步 `src/types/`。

## 5. 动效组件（ReactBits / anime.js 等）

- 动效叠加在统一的设计系统上（见 `hwict-ui-design`），先定风格档位再上
  动效，不逐页临时发挥。
- 首屏/演示页可用 ReactBits 组件与 anime.js 做第一印象；核心操作路径
  保持轻量，动画不得拖慢交互（决赛需现场答辩与复现）。
- 遵守 `prefers-reduced-motion`；禁用动画 width/height 等触发布局的属性，
  优先 transform/opacity。

## 6. 格式与检查

- 使用 oxfmt 和 `.oxfmtrc.json`；`pnpm format` 改格式，`pnpm format:check`
  只检查。
- 完成修改后运行 `pnpm build`（含 tsc）；按改动范围运行 ESLint，并检查
  相关页面交互、窄屏布局和控制台报错。
- 不通过关闭规则掩盖检查失败；检查受已有问题影响时明确说明，不将失败
  报告为通过。

## 7. 工程配置基线

`web/` 骨架建立时按以下基线落地，不依赖任何外部仓库：

- ESLint flat config（`eslint.config.js`）：TypeScript + React Hooks + TanStack
  Router/Query 规则；CI 用 `pnpm lint` 跑全量。
- 格式化：oxfmt + `.oxfmtrc.json`，`pnpm format` 改格式、`pnpm format:check`
  只检查。
- shadcn 配置：`components.json`（样式 new-york、语义色 token、`@/` 别名、
  Base UI 基座），生成件落在 `src/components/ui/`；`init`/`add` 后检查生成件
  使用 `render`，出现 `asChild` 说明基座选错。
- TypeScript：`strict: true`、路径别名 `@/* -> src/*`、
  `tsconfig.app.json` / `tsconfig.node.json` 分离。
- Vite：`@` 别名 + TanStack Router 插件生成 `src/routeTree.gen.ts`，构建产物
  在 `dist/`（已在 `.gitignore` 中忽略）。
- `web/package.json` 至少提供 `dev`、`build`、`typecheck`、`lint`、`format`、
  `format:check`、`test`、`test:watch`；骨架落地后以实际脚本为准，并同步
  README 验证速览与 `hwict-dev` 验证矩阵。

若团队另有前端基线仓库，先复制再按本清单逐项核对，把基线来源与差异记入当次
`docs/plans/` 计划，避免两套配置长期并存。
