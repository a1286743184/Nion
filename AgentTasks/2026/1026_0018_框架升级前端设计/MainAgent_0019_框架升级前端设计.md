# 框架升级前端设计定稿

## 完成内容

在用户指定的 `docs/framework-upgrade-design.md` 中补齐前端设计，保留现有五个底部入口、双侧面板、暖色主题和 sharedElement 动效，不新增另一套视觉体系。原 U-01～U-10 均已有明确决议与章节索引。

设计覆盖：

- 视觉变量、组件边界、任务/笔记混排、统计及空态文案。
- 单一 FAB 与类型覆盖、详情子项树/关联笔记、跨实体返回栈。
- 长按菜单与已有拖拽/多选手势仲裁，删除失败反馈。
- 预计时长与专注实际对比、排期结构化卡片及本地确认状态机。
- 每日时间窗、智能清单侧栏/设置、全屏高优提醒及厂商引导。
- 动效规格、减少动画、可访问性、Compose 性能与实机验收矩阵。

## 关键决策与依据

1. 实体 kind 决定卡片外观和打开方式；清单类型只决定创建默认值及语境。解决了当前按 `isNotebookView` 分流打开编辑器/详情的问题。
2. 长按后拖动保留排序，原地松手打开菜单，多选收进菜单；依据 `SharedTaskList.kt` 现有长按拖拽与松手多选逻辑。
3. 排期建议保存为随会话持久化的结构化数据，确认使用稳定 proposalId 和任务 id，并校验 originalReminder，避免旧建议覆盖用户新修改。当前聊天持久化机制见 `CompanionViewModel.kt:877-912`。
4. 删除采用「删除当前项、直接子项上移一级」，需要 B4 先实现 core 原子操作；当前 `delete_task` 只删除当前实体，直接子项 parent 会悬空，见 `core/src/nion_core.rs:553-575`。
5. 通知修复必须覆盖 Worker 和 `ReminderOverlay` 的通知取消点，不能仅修复 Worker；高优全屏展示始终可降级为 HIGH 通知。
6. 智能清单时间口径定为 reminder 本地日期；本周为周一至周日，最近 N 天含今天向前取 N 天；收集箱沿用 category_id 为空的真实聚合语义。

## 验证

- `git diff --check -- docs/framework-upgrade-design.md` 通过；仅有 Git 的 LF/CRLF 提示。
- Python 只读检查通过：文档可按 UTF-8 读取，10 张表格列数一致，U-01～U-10 均有覆盖，无 `[UI-DETAIL]` 占位。
- 已对照现有主题、任务列表/详情、聊天持久化、设置、日程、提醒及 core 删除/查询代码，标明实现依赖与行为冲突。

## 产出物

- `E:\Applications\SelfmadeTools\Nion\docs\framework-upgrade-design.md`：框架与前端交互定稿，第 7 节为前端规范，第 11 节为 UI 决议索引。
- 本文件：设计决策及验证记录。

## 边界

本轮为文档设计，不实现 Android UI，不执行构建或实机测试。原有未提交的 Kotlin、Gradle、UniFFI 与二进制改动均不属于本次交付，提交时按精确路径隔离。
