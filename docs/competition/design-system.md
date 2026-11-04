# HuaWei-ICT 设计系统（v0.1 基线）

状态：草案 v0.1。页面开工前由队长确认；确认后本文件是 `web/` 全项目视觉的
唯一事实源，每个页面照此实现，不各自发挥。

规则来源：`.agents/skills/hwict-ui-design/SKILL.md`（设计系统先行工作流与
十级优先规则）。调整视觉先改本文件，再全局生效。

## 1. 设计目标

- 评审第一印象：专业、统一、克制，不像模板拼装。
- 演示可复现：动效与图表不拖慢答辩现场交互（决赛有材料复现环节）。
- 可维护：颜色、字号、间距只从本文件的 token 取值。

## 2. 风格档位

现代科技感、信息密度适中；圆角克制、阴影少而浅；不使用玻璃拟态和大面积
渐变；全站只用一种风格语言。

## 3. 色彩

只允许通过语义 token（shadcn/ui 变量名）使用：

| token | 浅色 | 深色 | 用途 |
|---|---|---|---|
| `--background` | `#ffffff` | `#0b0f19` | 页面底色 |
| `--foreground` | `#111827` | `#e5e7eb` | 正文 |
| `--card` | `#ffffff` | `#111827` | 卡片 |
| `--primary` | `#c7000b` | `#ff5a5f` | 主操作（华为红） |
| `--primary-foreground` | `#ffffff` | `#0b0f19` | 主色上的文字 |
| `--secondary` | `#f3f4f6` | `#1f2937` | 次要面 |
| `--secondary-foreground` | `#111827` | `#e5e7eb` | 次要面上的文字 |
| `--muted` | `#f9fafb` | `#111827` | 弱化面 |
| `--muted-foreground` | `#6b7280` | `#9ca3af` | 辅助文字 |
| `--accent` | `#f3f4f6` | `#1f2937` | 悬停 / 选中面 |
| `--accent-foreground` | `#111827` | `#e5e7eb` | 悬停面上的文字 |
| `--popover` | `#ffffff` | `#111827` | 浮层底色 |
| `--popover-foreground` | `#111827` | `#e5e7eb` | 浮层文字 |
| `--border` | `#e5e7eb` | `#1f2937` | 分隔线 |
| `--input` | `#e5e7eb` | `#1f2937` | 输入框边框 |
| `--ring` | `#c7000b` | `#ff5a5f` | 键盘焦点环 |
| `--destructive` | `#dc2626` | `#f87171` | 破坏性操作 |
| `--destructive-foreground` | `#ffffff` | `#0b0f19` | 破坏性操作上的文字 |

- 禁止裸色值（如 `bg-blue-500`、`#1e40af`）；暗色模式只靠 token 自动适配，
  不手写 `dark:` 覆盖。
- 状态不只靠颜色传义：配合 Badge 文案或图标，对比度 ≥ 4.5:1。
- 键盘焦点必须可见：用 `--ring` 的 3px 外圈或等价的 `focus-visible` 样式，
  不得只移除 outline 而不给替代。

## 4. 字体与排版

- 字族：`Inter, "Noto Sans SC", system-ui, sans-serif`；数字用 `ui-monospace` 等宽。
- 字号阶梯（rem）：12 / 14 / 16 / 20 / 24 / 32，不出现小于 12px 的正文。
- 正文 16px、行高 1.6；表格与辅助文字 14px、行高 1.5。
- 标题按语义用 `h1`–`h6`，视觉大小用字号类，不跳级、不借标题标签凑样式。

## 5. 间距、圆角、密度

- 间距基数 4px，常用 8 / 12 / 16 / 24 / 32；布局用 flex/grid + `gap-*`，
  禁用 `space-x-*` / `space-y-*`。
- 圆角：卡片与弹层 8px，按钮与输入 6px，徽标全圆。
- 阴影只用于浮层（Dialog / Sheet / Dropdown），内容区用边框分隔。
- 内容区最大宽度 1200px，桌面左右留白 24px，移动端 16px。

## 6. 图标与图片

- 图标统一 lucide-react 单色线性，随字号缩放，不手调 margin。
- 图片用 WebP/AVIF，显式标注宽高防布局抖动，必须有 `alt`。

## 7. 动效

- **档一 微交互**：120–200ms，`ease-out`，全局默认（按钮、输入、hover）。
- **档二 引导**：200–350ms，首屏入场或结果揭示，一页最多一处。
- **档三 演示级**：只用于首屏/演示页，ReactBits / anime.js 编排；上档前先
  自问"评委现场答辩时会不会卡"。
- 只用 `transform` / `opacity`，不动画 width/height；`prefers-reduced-motion:
  reduce` 时全部降级为无动画。

## 8. 组件基线

- 页面骨架：顶部导航 + 主内容 + 页脚；移动端导航折叠为抽屉。
- 每个数据视图必须有加载（Skeleton）、错误（Alert + 重试）、空（Empty）三态。
- 弹层必须有 Title；表格在自身容器内横向滚动；长文本 `truncate`。
- 优先组合 `src/components/ui/` 已有组件，不改内部实现（见 `hwict-shadcn`）。
- 组件原语：Base UI，语义标签用 `render` 传递；非 `button` 元素同时传
  `nativeButton={false}`，不用 `asChild`（见 `hwict-shadcn`）。

## 9. 响应式断点

`sm 640` / `md 768` / `lg 1024` / `xl 1280`；移动优先，`md` 以下单列布局。

## 10. 交付前走查

按 `.agents/skills/hwict-ui-design/SKILL.md` 第 2 节 1–6 级逐项确认（演示页加 7 级），结论写进
当次 `docs/logs/` 开发日志；不通过的项修复后再交付。

## 11. 变更流程

- 改视觉先改本文件，再全局生效；不在单页打补丁。
- 提交时在 PR 描述中说明动机、影响页面与是否需要重新走查，由队长确认。
