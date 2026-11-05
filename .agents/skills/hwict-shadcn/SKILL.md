---
name: hwict-shadcn
description: HuaWei-ICT 前端 shadcn/ui 组件使用规范。当在 web/ 目录下使用 shadcn 组件、组合界面、添加或更新组件时自动加载。触发场景：组合 shadcn 组件、写 Tailwind 类名、添加/更新 src/components/ui 下的组件、表单与弹层类 UI 搭建。
when_to_use: 使用 / 组合 / 添加 shadcn 组件 时
user-invocable: true
---

# HuaWei-ICT shadcn/ui 组件规范

> 规则改编自 shadcn/ui 官方 skill（MIT）的公开规则，为自写精简版；
> CLI 工作流以官方文档为准。

## 1. 组合优先

- 写界面前先检索 `src/components/ui/` 已有组件，能用组合解决的不新写
  （例：设置页 = Tabs + Card + 表单控件）。
- 优先使用组件内置 variants，不为一次性样式复制改造组件；确需新形态，
  在使用处组合，不修改 `ui/` 内部实现。

## 2. 样式与 Tailwind 纪律

- **语义色强制**：只用 `bg-primary`、`text-muted-foreground` 等设计 token，
  禁止 `bg-blue-500` 之类裸色值；暗色模式靠 token 自动适配，禁手动
  `dark:` 覆盖。
- 间距用 `flex`/`grid` + `gap-*`，**禁用 `space-x-*` / `space-y-*`**。
- 等宽高等方形尺寸用 `size-*`；文本截断用 `truncate`。
- 条件类名用 `cn()`（clsx + tailwind-merge）合并，不拼字符串。
- overlay 类组件不手写 z-index，交给组件体系。

```tsx
// ✅ 正确
<div className="flex items-center gap-2">
  <Badge variant="secondary">{status}</Badge>
</div>

// ❌ 错误：裸色值 + space-y
<div className="space-y-2 bg-blue-500">
  <Badge className="bg-gray-200">{status}</Badge>
</div>
```

## 3. 组件结构硬规则

- 弹层（Dialog / Sheet / Drawer）必须有 Title（可用 `sr-only`），否则
  可访问性与结构报错。
- `TabsTrigger` 必须在 `TabsList` 内；`Avatar` 必须带 `AvatarFallback`；
  Item 类内容放进对应 Group 容器。
- 组件基座以 `web/components.json` 为准：本项目已确认 Base UI，统一用
  `render`；`render` 换成 `a`/`Link` 时同时传 `nativeButton={false}`。
  Radix 教程里的 `asChild` 不要带进来，也不要混用两套写法。
- 按钮无 isPending 属性：加载态用 `Spinner` + `disabled` 组合表达。

## 4. 用组件、不自制

- 提示信息用 `Alert`；空状态用 `Empty`；分隔用 `Separator`（不用
  `<hr>`）；加载占位用 `Skeleton`（不自制 animate-pulse 块）；标签用
  `Badge`。
- toast 统一用项目既选方案（sonner），不混用多套通知。

## 5. 图标

- 图标组件不加尺寸类，随容器缩放；行内图标对齐交给组件的图标插槽/
  `data-icon` 约定，不手动调 margin。

## 6. 表单

- 表单结构用 shadcn 的 Field 体系（`FieldGroup` + `Field` + 专用输入组
  件），不用裸 div + Label 自行拼装。
- 校验状态走 Field 的 invalid 约定（`data-invalid` / `aria-invalid`），
  错误文案贴近字段展示（细则见 `hwict-ui-design` 第 6 级）。

## 7. CLI 要点（工程骨架建立后生效）

- shadcn 自 2026-07 起默认 Base UI；`init` 若提示选择基座，选 Base UI。
- 添加组件：`pnpm dlx shadcn@latest add <component>`；添加后**审查生成
  文件**；出现 `asChild` 或 Radix 专有属性，说明基座选错，先修配置再继续。
- 更新组件先用 `--dry-run` / `--diff` 查看差异，未经队长同意不得
  `--overwrite`；不从 GitHub 手抓组件文件。
