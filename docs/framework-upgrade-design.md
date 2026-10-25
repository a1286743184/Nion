# Nion 框架级升级设计 —— 实体统一、工具诚实化、排期能力与通道修复

> 状态：框架定稿（2026-10-25），UI 级细节待填充
> 依据：2026-10-25《功能问题反馈报告》（清单 id=2 复测）+ 代码核查结论
> 文档定位：**只定框架**——数据模型、接口契约、职责边界、批次划分与验收标准。
> 所有标注 `[UI-DETAIL]` 的条目是留给 UI 级设计的占位，汇总见文末第 11 节。

---

## 1. 背景与问题全景

### 1.1 现状架构事实（均已核查到代码）

| # | 事实 | 证据 |
|---|------|------|
| F1 | 笔记与任务是同一张表的同一种实体，实体层无类型字段 | `core/src/models.rs:22-45`（TaskData 无 kind）；建表语句 `core/src/nion_core.rs:44` |
| F2 | 类型唯一载体是清单：`checklists.checklist_type` | `core/src/nion_core.rs:146`（迁移）；`models.rs:11-12` 注释 |
| F3 | 笔记的创建 = `createTask` 别名；AI 工具层 note 通道不解析 parent 参数 | `UnifiedTools.kt:880-906`（executeCreateNote） |
| F4 | update 假失败：先 `updateTaskParent` 落库成功，再无条件调 `updateTask`；仅归属变更时后者收全空参数报 `No fields to update` | `UnifiedTools.kt:1366-1410`；`nion_core.rs:522-526` |
| F5 | 笔记搜索只搜根节点（`parent_id IS NULL`），子笔记对搜索不可见 | `nion_core.rs:1772、1792`；UI 调用方 `NoteSearchScreen.kt:89`、`TaskViewModel.kt:1136`、`UnifiedTools.kt:1837` |
| F6 | UI 渲染决策权在清单级（`isNotebookView`），实体自身无发言权 | `TaskViewModel.kt:156`；`SharedTaskCard.kt:257` |
| F7 | 息屏提醒失效根因：Worker 启动浮窗分支会撤掉系统通知，息屏时浮窗不可见且通知已消失 | `ReminderWorker.kt:85-90`（dismissNotification） |
| F8 | 提醒基建本身正确：`setExactAndAllowWhileIdle` + BootReceiver 重排 + 通知权限已运行时请求 | `ReminderScheduler.kt:58`；`BootReceiver.kt:32`；`MainActivity.kt:63` |
| F9 | 记忆机制齐全但不触发：工具已注册、描述要求主动记录、记忆注入 prompt；但系统提示词正文无行为规则 | `ToolRegistry.kt:48`；`MemoryTool.kt:36-39`；`CompanionViewModel.kt:2395+` |
| F10 | 智能清单是硬编码特判，仅今天/收集箱两个 | `TaskViewModel.kt:106、142-151、291+` |
| F11 | 专注累计基建已存在，缺「预期时长」与「预期 vs 实际」对比 | `TaskData.focus_seconds`；`focus_sessions` 表 `nion_core.rs:101`；FocusStats |
| F12 | 任务详情浮层无子任务列表、无关联笔记展示；关联入口只有笔记侧 | `TaskDetailOverlay.kt`（仅 DETAIL/ADD_SUBTASK/日期/附件四面板）；`TaskScreen.kt:256` 一带 |
| F13 | 关联机制本身是双向的，Rust 接口一次写两边 | `nion_core.rs:1808+`（link_task_and_note） |

### 1.2 原设计意图判定（决定我们保留什么）

原范式是「文档列表 + 容器定类型」：笔记 = 位于 notebook 型清单中的 task 行，渲染、搜索、统计全部由清单类型推导。该范式被两个演进打破：主列表换成共享树形组件（子笔记合法可见）+ AI 工具层跨通道操作（task 通道的 parent 用在笔记上）。

**升级方向遵循滴答清单模型**：任务与笔记是同一种实体的两种 `kind`，可互转，区别仅是能否勾选完成。原范式里自洽的部分（容器决定默认值、文档式搜索语义）保留；被打破的部分（实体无类型发言权、读写通道分叉）修正。

### 1.3 实测问题登记（已定位，修法归入各模块）

- AI 称「任务应该是平铺的」→ 实为模型撞上 F4 假失败后自圆其说；companion 全部代码无「平铺」约束。
- 笔记空态文案「暂无任务/添加第一个任务」：`TaskScreen.kt:1363-1368` 无 isNotebook 分支。
- 笔记清单顶部统计「N项待办 · N项已完成」：`TaskScreen.kt:629` 无条件渲染。
- 笔记编辑器顶距过近：`NotebookEditor.kt:131-135` 故意只取状态栏高度 60%。
- 关联任务 chip 一触即删：`NotebookEditor.kt:338` onClick 直调 onUnlinkTask。
- 子任务分组可设置但不生效（筛选只按根任务折叠树）→ 交互层误导，处理见 7.4。

---

## 2. 设计原则（后续所有设计的裁决准绳）

1. **单实体原则**：task/note 是同一实体（tasks 表）的两种 kind；类型是实体的属性，清单类型只决定新建默认值与视图语境，不再决定渲染。
2. **点任务原则**：不引入「结束时间」。时间占用由 `reminder`（起点）+ `expected_minutes`（时长）派生；排期是派生视图，不是核心模型。任何新增字段不得与既有字段形成隐式耦合。
3. **工具诚实性原则**：工具返回必须与数据真相一致。写入成功不得报错；目标状态已达成视为成功（幂等）；部分成功必须如实报告已应用部分。
4. **通道统一原则**：同一数据只有一个权威读写通道。note/task 两个透镜共享同一底层实现，返回结构统一，元素携带 `kind`。
5. **通知兜底原则**：任何提醒的增强展示形态（浮窗等）必须以系统通知为兜底，任何代码路径不得在展示增强时撤除兜底。
6. **复用优先**：能用既有字段/表/接口表达的不新增（focus_seconds、focus_sessions、linked_task_ids、resolveTaskOwnership 等全部复用）。

---

## 3. 数据层设计（core / SQLite）

### 3.1 Schema 变更

```sql
ALTER TABLE tasks ADD COLUMN kind TEXT NOT NULL DEFAULT 'task';      -- 'task' | 'note'
ALTER TABLE tasks ADD COLUMN expected_minutes INTEGER;               -- NULL = 未规划时长
```

- 迁移沿用现有 lenient ALTER 链模式（`nion_core.rs:133-148`）。
- 当前为测试期（无真实用户数据）：迁移后允许一次性 `UPDATE tasks SET kind='note' WHERE category_id IN (SELECT id FROM checklists WHERE checklist_type='notebook')` 刷存量。正式发布前须移除此一次性刷写、冻结迁移语义并升 schema 版本。
- `TaskData` 增加 `kind: String`、`expected_minutes: Option<i64>`（uniffi Record 变更 → 重新生成 Kotlin 绑定、重编两个 ABI 的 `.so`，流程按 `build-android.sh`）。

### 3.2 实体类型语义

- `kind='task'`：可勾选，status/completed_at 语义完整。
- `kind='note'`：无勾选语义；status 字段物理保留但 UI 与工具层不赋予语义。
- **互转** = `update(kind=...)`：
  - task→note：completed_at 保留（转回 task 时恢复已完成状态显示）；
  - note→task：status 从 `todo` 起；
  - focus_seconds / focus_sessions / linked_task_ids / 附件全部跟随实体保留（属于实体，不属于类型）。
- **创建时 kind 来源**：AI 通道 `create(entity_type=note)` → kind='note'，`entity_type=task` → kind='task'；UI 在笔记型清单新建 → 默认 'note'，任务型清单 → 默认 'task'；创建时均可显式覆盖 `[UI-DETAIL]` 覆盖入口。
- **渲染决策权迁移**：SharedTaskCard / SharedTaskList 以实体 `kind` 决定图标/勾选框/描述渲染；`isNotebook` 参数退役为「新建默认值提示」，不再驱动已有实体渲染（见 7.1）。

### 3.3 组合更新事务化（消灭假失败的根）

现状：归属变更（parent/group/checklist）走独立接口各自自动提交，字段更新走 `update_task`，组合操作无事务，且后者在仅归属变更时报错（F4）。

新增 Rust 接口（一次锁、一个事务内完成全部变更）：

```rust
/// 组合更新：字段 + 归属一次提交。
/// FFI 约束：UniFFI 不支持嵌套 Option，"未提供 / 置空" 用显式标志位区分。
pub fn update_task_full(
    &self,
    id: String,
    name: Option<String>,
    description: Option<String>,
    priority: Option<String>,
    status: Option<String>,
    kind: Option<String>,              // "task" | "note"
    expected_minutes: Option<i64>,
    category_id: Option<String>,
    set_group: bool, group_id: Option<String>,   // set_group=true 时应用（group_id 可为 None=清除）
    set_parent: bool, parent_id: Option<String>, // set_parent=true 时应用（可为 None=提升为根）
    reminder: Option<String>,
    recurrence_rule: Option<String>,
    recurrence_reminder_time: Option<String>,
) -> Result<TaskData, NionError>
```

事务内步骤：环校验（沿 parent 链上溯，拒绝 A→B→A，含自环）→ 写入全部字段 → 级联子孙 category/group（复用 `cascade_to_descendants`）→ 返回完整实体。

语义修正：
- `update_task` 保留给单字段路径，其 `No fields to update` 守卫保留（防空调用），但工具层不得再产生「归属接口 + 空 updateTask」组合。
- Kotlin 工具层 `executeUpdateTask` 改为：先解析校验全部参数 → 一次调用 `update_task_full`。原 `updateTaskParent`/`updateTaskGroup` 独立调用点保留给 UI 现有路径（`TaskViewModel.kt:690、707`），后续可逐步收敛。

### 3.4 查询接口

```rust
/// query=None 且 checklist_id 给定 → 枚举该清单全部笔记。
/// include_children=false 保持文档式搜索（只搜根）；true 时包含子笔记。
/// UI 调用方一律 false（保持现状语义）；AI 调用方 true（需要完整视图）。
pub fn search_notes(
    &self,
    query: Option<String>,
    checklist_id: Option<String>,
    include_children: bool,
) -> Result<Vec<TaskData>, NionError>
```

- `get_tasks` / `get_tasks_by_category` 系列自动携带 kind（TaskData 字段）。
- 收集箱（INBOX）聚合语义现状待核对（预计为 category_id 为空的任务），实现批次 5 时先核实再抽象。 `[待核对]`

---

## 4. AI 工具层契约（UnifiedTools）

### 4.1 诚实性规范（所有工具强制）

1. 先校验后执行：全部参数解析失败时，零写入并返回明确错误。
2. 幂等：目标状态已达成 → 返回 success + 当前实体（不产生额外副作用）。
3. 字段构建统一：id / ids / name / names 四条定位路径必须共享同一个「参数 → 变更集」构建函数，消灭分支行为分叉（F4 的结构性根源）。建议实现形态：`TaskChangeSet.fromParams(params, core): TaskChangeSet?`，各路径只负责把定位符解析成 id 列表，变更集构建与执行完全共用。
4. 返回值与数据一致：批量操作逐项如实报告；不存在「报错但已写入」。

### 4.2 note/task 通道统一

- `create`：note 通道支持 parent（复用 `resolveTaskOwnership`），并按 entity_type 写入 kind。batch items 同步支持。
- `query`：
  - note 通道 name 变为可选；无 name 时按 checklist（可选）枚举；
  - name 有值时全文搜索（include_children=true）；
  - task 通道不变，返回元素自然携带 kind。
- 返回结构统一：实体查询一律 `{ "items": [...], "count": n }`，元素含 `kind`。旧顶层 key（tasks/notes）直接废弃——唯一消费方是 LLM 与 CompanionViewModel，实现时 grep `optJSONArray("notes")` 类解析点确认无遗漏。
- `update`：note 等同 task（现有 effectiveType 映射保留），走 3.3 的 `update_task_full`；`kind` 成为可更新字段（互转入口）。
- `delete`：不变（note 已等同 task）。
- 工具 schema 描述统一改写为互转语义：「笔记与任务是同一种实体，可互转，区别是能否勾选完成；笔记本内可存在任务，任务清单内可存在笔记」。消除 AI 的类型心智偏差。

### 4.3 新增 schedule 工具（排期）

**输入契约**：

```json
{
  "tasks": [ { "name" 或 "id", "expected_minutes"? } ],
  "fragmented": [ { "name", "minutes_per_day", "chunk_minutes"? } ],
  "time_windows": [ { "start": "09:00", "end": "11:00" } ],
  "start_date": "YYYY-MM-DD",
  "confirm": false
}
```

- `time_windows` 缺省时读取设置 `daily_time_windows`（见 4.3.1）。
- 各任务 expected_minutes 缺省时：查任务字段；仍缺失 → 该任务进 `unplaced` 并说明原因（诚实性：不猜时长）。
- 已占用块自动推导：现有任务中 `reminder + expected_minutes` 落在窗口内的区间视为已占用；无 reminder 的任务不参与。
- `fragmented` 为可碎片化任务（如「每日行测刷题 1 小时，可拆 30 分钟块」），均匀插入空隙。

**输出契约**：

```json
{
  "assignments": [ { "task": "...", "date": "...", "start": "09:00", "end": "11:30" } ],
  "earliest_completion": "YYYY-MM-DD",
  "unplaced": [ { "task": "...", "reason": "缺少 expected_minutes" } ],
  "confirmed": false
}
```

**算法与边界**：按优先级 + 建议截止排序的贪心装窗；不做约束求解器、不做跨天拆分（后期可选）、排期上限 90 天。**排期结果是建议**：`confirm=true` 才批量回写各任务的 reminder（一次性 update 循环，逐项如实报告）。`[UI-DETAIL]` 排期结果在聊天中的卡片呈现。

**4.3.1 设置项**：`daily_time_windows`（JSON：`[{"start":"09:00","end":"11:00"},...]`），V1 每天相同；V2 可按星期差异化。`[UI-DETAIL]` 设置页表单。

### 4.4 记忆触发

机制链路不动（F9 全部保留）。补系统提示词行为规则，注入位置为 `CompanionViewModel.kt:2368+` 的稳定前缀构建处。规则框架（措辞属 prompt 设计，后续迭代）：

> 当用户在对话中透露稳定偏好、日程习惯、身份事实或明确要求记住某事时，先调用 memory 工具记录，再继续回复；同类记忆优先 update 而非新增。

可选强化：每轮回复后的轻量检查点（判分是否应记录），成本高则不做，先观察提示词规则的效果。

---

## 5. 智能清单框架

- 定义注册表替代硬编码特判（F10）：

```kotlin
data class SmartListDef(
    val id: String,        // "smart_today" / "smart_tomorrow" / "smart_week" / "smart_7d" / "smart_30d" / "inbox"
    val label: String,     // 今天 / 明天 / 本周 / 最近7天 / 最近30天 / 收集箱
    val filter: (TaskData) -> Boolean,   // 统一聚合函数
)
```

- 内置全集：今天、明天、本周、最近 7 天、最近 30 天、收集箱；设置页勾选启用集合（存 settings 表）。
- `TaskViewModel` 的 TODAY_ID / INBOX_ID 特判（106、142-151、291+）全部迁移为注册表遍历；「今天」「收集箱」保持默认启用，保证现有体验不变。
- 智能清单不可删除、不可重命名；顺序固定在自建清单之前。`[UI-DETAIL]` 侧栏分组形态、设置页交互。
- 本周/最近 N 天的聚合口径（按 reminder 日期还是 created_at）实现时定稿，倾向 reminder（有时间意图的任务才进日程视图）。

---

## 6. 提醒通道设计

- 触发链不变：`AlarmManager.setExactAndAllowWhileIdle` → `ReminderReceiver` → `ReminderWorker`（F8 基建正确）。
- **展示决策反转：通知为必达兜底，浮窗为增强层**。
  - 修复 F7：启动浮窗的分支不得 `dismissNotification`（ReminderWorker.kt:85-90）；具体分支结构实现时核对，原则是任何路径下系统通知保留，由用户点击/处理后才清除。
  - 息屏/锁屏：依赖通知本身；priority=high 的任务走 `fullScreenIntent` 直接唤亮展示 `[UI-DETAIL]` 通知样式与全屏页。
  - 亮屏且应用进程存活：通知 + 浮窗并存（现状行为保留，仅不再撤通知）。
- 对照参考：`D:\DevEnv\SymNex\android`（用户既有成熟实现）核对厂商电池白名单与厂商 ROM 差异处理（Nion 已有 `VendorBatteryHelper`，复用）。

---

## 7. UI 层框架（细节占位）

### 7.1 渲染决策权迁移

- SharedTaskCard / SharedTaskList / 统计与空态组件：渲染分支条件从「清单是否 notebook」改为「实体 kind」。
- 笔记清单与任务清单的差异收窄为：新建默认 kind、空态/统计文案语境、编辑入口形态。
- `isNotebookView` 保留用于「新建默认值」与文案，不再参与已有实体渲染。

### 7.2 任务详情浮层信息架构

详情浮层在现有四面板（DETAIL/ADD_SUBTASK/日期/附件）基础上，DETAIL 面板固定增加两个区块（数据全部现成，F12/F13）：

- **子任务区**：本任务全部子孙的树形列表（parent_id 现成），点击子项跳转其详情；数量徽标。
- **关联笔记区**：linked_task_ids 对应笔记的标题列表，点击跳转笔记编辑器；「添加关联」入口（对称复用笔记侧已有的双向接口 F13）。

`[UI-DETAIL]` 两区块布局、层级缩进、跳转动效、空态。

### 7.3 长按操作菜单

- 框架：可扩展操作项列表，首版枚举：移动到清单 / 改分组 / 转为任务或笔记（按当前 kind 显示反向项）/ 删除（保留现有）。
- 全部操作走 3.3 `update_task_full` 或既有接口，菜单只是入口聚合。`[UI-DETAIL]` 菜单形态（底部抽屉/上下文菜单）、destructive 确认交互。

### 7.4 已知误导性交互的处理口径

- 子任务分组选择：V1 隐藏子任务创建表单中的分组选择（根任务的分组即整棵树的分组），消除「可设但不生效」；若产品后续要子级独立分组，需先把筛选模型改为按节点过滤，成本另评。`[UI-DETAIL]` 表单裁剪。
- 笔记编辑器顶距：改全量 `statusBarsPadding`（替换 NotebookEditor.kt:131-135 的 60% 折减）。
- 关联任务 chip：点击 = 跳转到该任务；删除 = 长按或独立叉形角标（替换 :338 的 onClick 直删）。

### 7.5 文案条件化

- 空态（TaskScreen.kt:1363）与顶部统计（:629）按「清单类型 + kind 分布」条件化；笔记语境显示「N 条笔记」。`[UI-DETAIL]` 完整文案表。

---

## 8. 实施批次与依赖

| 批次 | 内容 | 依赖 | 规模预估 |
|------|------|------|----------|
| B1 工具诚实化 | 3.3 组合接口 + 4.1 + 4.2（note 查询参数化、返回统一、create note 支持 parent） | 无 | 小（core + UnifiedTools） |
| B2 提醒修复 | 6（撤通知修复 + fullScreenIntent） | 无，独立可提前 | 小 |
| B3 kind 落地 | 3.1/3.2 schema + 7.1 渲染迁移 + 7.5 文案 + 工具层 kind 字段 | B1（工具层先稳定） | 中（含绑定再生成） |
| B4 详情与操作 UI | 7.2 / 7.3 / 7.4 / 关联 chip、顶距 | B3（渲染决策权先迁移） | 中 |
| B5 智能清单 | 5 | 无强依赖 | 小-中 |
| B6 排期能力 | 3.1 expected_minutes + 4.3 schedule 工具 + 专注对比展示 + 4.3.1 设置 | B1（诚实性规范） | 中-大，可拆两期：先字段+对比，后排期 |

批次内顺序建议按表执行；B2 可按体感优先级随时插入。

## 9. 验收标准（逐批次）

- **B1**：反馈报告 P2/P3/P4 三场景复测——update(ids, parent) 返回 success 且与数据一致；仅 parent 变更不再报错；query(note) 无 name 可枚举、精确名搜索子笔记可见。全工具链「报错但已写入」为零。
- **B2**：息屏状态下到达提醒时间的任务产生系统通知（高优全屏唤亮）；亮屏行为不回退。
- **B3**：同一笔记本内同时存在可勾选任务与不可勾选笔记；双向互转状态正确（完成态保留/重置规则）；任务清单内可建笔记；统计/空态文案随语境正确。
- **B4**：任务详情可见子孙树并可跳转；关联笔记双向可见可跳转；长按菜单四操作可用。
- **B5**：设置启用「本周」后侧栏出现并正确聚合；默认视图与现状无差异。
- **B6**：用户排期案例可解——定海神针卷（一次性 150 分钟）+ 行测判断推理系列课（59/40/90/37/60/79/93 分钟序列）+ 每日三时间窗（09-11/14-17/20-24）+ 每日 60 分钟碎片刷题，输出各任务时间安排与最早完成日期；expected_minutes 缺失的任务如实进 unplaced。
- 通用：每批次完成后 `cargo test -p nion-core` + `assembleDebug` 通过；涉及 core 变更的批次重新生成 UniFFI 绑定并部署验证。

## 10. 风险与边界

- **UniFFI 约束**：嵌套 Option 不支持，3.3 已用标志位规避；Record 字段变更要求 Kotlin 侧与 `.so` 严格同版本部署（老 APK + 新 so 或反之会崩溃），每批次部署走完整 `build-android.sh` + `deploy.sh`。
- **测试期迁移**：3.1 的一次性存量刷写在正式发布前必须移除并冻结语义（当前无真实用户数据，风险窗口可控）。
- **贪心排期边界**：不处理复杂约束（依赖关系、场地、人员）；跨天任务不拆分；结果定位是「建议」，最终由用户 confirm。
- **通知厂商差异**：国产 ROM 电池白名单可能导致 AlarmManager 仍被杀，复用 VendorBatteryHelper 并对照 SymNex 实现补引导页。`[UI-DETAIL]`
- **prompt 效果不确定**：4.4 记忆规则上线后需观察记录频率，过阈值（每轮都在记）则收紧措辞。

## 11. UI 细节待填清单（后续设计者的工作列表）

| 编号 | 位置 | 待填内容 | 状态 |
|------|------|----------|------|
| U-01 | 3.2 / 7.1 | 创建时的 kind 覆盖入口（新建任务/笔记的切换 UI） | 待填 |
| U-02 | 4.3 | schedule 排期结果在聊天中的卡片呈现 | 待填 |
| U-03 | 4.3.1 | 每日时间窗设置页表单（V1 每天相同） | 待填 |
| U-04 | 5 | 智能清单设置页 + 侧栏分组形态 | 待填 |
| U-05 | 6 | 高优 fullScreenIntent 的通知样式与全屏页 | 待填 |
| U-06 | 6 | 厂商电池白名单引导页 | 待填 |
| U-07 | 7.2 | 详情浮层子任务区/关联笔记区布局与跳转动效 | 待填 |
| U-08 | 7.3 | 长按菜单形态与删除确认交互 | 待填 |
| U-09 | 7.4 | 子任务创建表单裁剪（隐藏分组选择） | 待填 |
| U-10 | 7.5 | 空态/统计完整文案表 | 待填 |

## 附录 A：本文档引用的代码事实索引

F1 `core/src/models.rs:22-45`、`core/src/nion_core.rs:44` ｜ F2 `nion_core.rs:146`、`models.rs:11-12` ｜ F3 `UnifiedTools.kt:880-906` ｜ F4 `UnifiedTools.kt:1366-1410`、`nion_core.rs:522-526` ｜ F5 `nion_core.rs:1772/1792`、`NoteSearchScreen.kt:89`、`TaskViewModel.kt:1136`、`UnifiedTools.kt:1837` ｜ F6 `TaskViewModel.kt:156`、`SharedTaskCard.kt:257` ｜ F7 `ReminderWorker.kt:85-90` ｜ F8 `ReminderScheduler.kt:58`、`BootReceiver.kt:32`、`MainActivity.kt:63` ｜ F9 `ToolRegistry.kt:48`、`MemoryTool.kt:36-39`、`CompanionViewModel.kt:2395+` ｜ F10 `TaskViewModel.kt:106/142-151/291+` ｜ F11 `models.rs` TaskData、`nion_core.rs:101` ｜ F12 `TaskDetailOverlay.kt` ｜ F13 `nion_core.rs:1808+`

参考项目：`D:\DevEnv\SymNex\android`（用户既有实现，提醒与任务操作交互的对照来源）。
