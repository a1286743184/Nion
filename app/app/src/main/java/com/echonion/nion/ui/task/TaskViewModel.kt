package com.echonion.nion.ui.task

import android.app.Application
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Stable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.echonion.nion.core
import com.echonion.nion.dataEvents
import com.echonion.nion.reminder.NotificationHelper
import com.echonion.nion.reminder.ReminderScheduler
import com.echonion.nion.reminder.ReminderStore
import com.echonion.nion.ui.companion.tools.DataType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import uniffi.nion_core.NionCore
import uniffi.nion_core.TaskData

@Stable
data class TaskItem(
    val id: String,
    val name: String,
    val description: String?,
    val priority: String,
    val isDone: Boolean,
    val createdAt: String,
    val subtasks: List<TaskItem> = emptyList(),
    /** 该任务累计专注秒数，来自 Rust 端 focus_seconds 字段 */
    val focusSeconds: Long = 0,
    /** 循环规则：null 或 "none" 表示不循环，"daily" 表示每日循环 */
    val recurrenceRule: String? = null,
    /** 每日循环的提醒时间，格式 "HH:MM"，仅当 recurrenceRule="daily" 时有效 */
    val recurrenceReminderTime: String? = null,
    /** 一次性提醒时间，格式 "YYYY-MM-DDTHH:MM"，null 表示未设置 */
    val reminder: String? = null,
    /** 是否为每日循环任务（recurrenceRule == "daily"） */
    val isDaily: Boolean = false,
    /** 当前查看日期的完成状态。每日任务看 completions 表，普通任务看 status */
    val isCompletedForDate: Boolean = false,
    /**
     * 实体类型："task"（可勾选）或 "note"（笔记）。
     * B3 起外观由它决定，不再看所在清单是不是笔记型。
     */
    val kind: String = "task",
    /** 预计总工作量（分钟）；null = 未规划。用于卡片「预计 N 分钟」与专注对比 */
    val expectedMinutes: Long? = null,
    /** 父级 ID；null = 顶层实体。用于详情返回栈与子项树 */
    val parentId: String? = null,
    /** 所属清单 ID；用于「移动清单」菜单显示当前值与跨清单跳转 */
    val categoryId: String? = null,
    /** 所属分组 ID；用于「更改分组」菜单显示当前值 */
    val groupId: String? = null,
    /** 关联实体 ID 原始串（逗号分隔）。UI 只用于计数，详情页走 core 查询拿完整数据 */
    val linkedIds: String? = null,
) {
    /** 是否笔记实体（不可勾选、无完成态） */
    val isNote: Boolean get() = kind == "note"
}

@Stable
data class ChecklistItem(
    val id: String,
    val name: String,
    /** 清单类型："task" = 任务型（默认），"notebook" = 笔记型 */
    val checklistType: String = "task",
)

/**
 * 分组 UI 模型 —— 对应 Rust 端的 GroupData
 * 用于在清单下展示二级分类（如"语文"、"英语"）
 */
@Stable
data class GroupItem(
    val id: String,
    val name: String,
    val checklistId: String,
    val color: String?,
)

@Stable
data class FlatTaskItem(
    val task: TaskItem,
    val depth: Int,
    val parentId: String?,
    val isGroupFirst: Boolean,
    val isGroupLast: Boolean,
    /** 是否处于折叠状态（仅对有子任务的主任务有意义） */
    val isCollapsed: Boolean = false,
)

@OptIn(FlowPreview::class)
class TaskViewModel(
    private val core: NionCore,
    private val onError: (String) -> Unit,
    private val app: android.app.Application,
) : ViewModel() {

    var tasks by mutableStateOf<List<TaskItem>>(emptyList())
        private set

    var checklists by mutableStateOf<List<ChecklistItem>>(emptyList())
        private set

    /**
     * 当前活跃清单 ID。
     * "today" = "今天"跨清单聚合视图；
     * "inbox" = "收集箱"孤儿任务视图（category_id = null 的任务）；
     * 其他 = 真实清单 ID。
     */
    var activeChecklistId by mutableStateOf<String?>(TODAY_ID)
        private set

    /** 当前清单下的分组列表 */
    var groups by mutableStateOf<List<GroupItem>>(emptyList())
        private set

    /**
     * 当前选中的分组 ID。
     * null 表示显示全部任务（不按分组过滤）
     */
    var activeGroupId by mutableStateOf<String?>(null)
        private set

    var checklistCounts by mutableStateOf<Map<String?, Pair<Int, Int>>>(emptyMap())
        private set

    val todoTasks: List<TaskItem> by derivedStateOf { tasks.filter { !it.isDone } }
    val doneTasks: List<TaskItem> by derivedStateOf { tasks.filter { it.isDone } }
    /**
     * 扁平化已完成任务 —— 保持母子分组关系。
     * 已完成父任务 = 组根；已完成子任务 = 组的成员，通过 depth / isGroupFirst / isGroupLast 渲染连接卡片。
     */
    val flatDoneTasks: List<FlatTaskItem> by derivedStateOf {
        val result = mutableListOf<FlatTaskItem>()
        addDoneGroups(tasks, baseDepth = 0, result)
        result
    }
    val todoCount: Int by derivedStateOf { flatTodoTasks.size }
    val doneCount: Int by derivedStateOf { flatDoneTasks.size }

    /** 扁平化待办任务 —— 遍历全量 tasks，折叠的子任务不加入列表 */
    val flatTodoTasks: List<FlatTaskItem> by derivedStateOf { flattenWithGroupInfo(tasks, collapsedTaskIds) }

    val activeChecklistName: String
        get() {
            // 智能清单名称从注册表取（新增清单无需再改这里）
            SmartLists.find(activeChecklistId)?.let { return it.label }
            return if (activeChecklistId == null) "我的任务"
            else checklists.find { it.id == activeChecklistId }?.name ?: "我的任务"
        }

    /** 当前激活清单的类型："task" 或 "notebook"。虚拟清单（今天/收集箱）始终为 "task" */
    val activeChecklistType: String
        get() {
            // 智能清单是聚合视图，没有清单类型；新建默认值按 task 处理（7.6）
            if (SmartLists.isSmart(activeChecklistId) || activeChecklistId == null) return "task"
            return checklists.find { it.id == activeChecklistId }?.checklistType ?: "task"
        }

    /** 当前是否在笔记型清单视图中 */
    val isNotebookView: Boolean by derivedStateOf { activeChecklistType == "notebook" }

    /**
     * 当前视图里各类实体的计数 —— 供顶部副标题与空态文案使用（7.4）。
     *
     * 口径：
     * - 遍历全部子孙实体（不是只看顶层），否则父项展开/折叠会改变统计；
     * - 笔记计数按实体 kind，不按清单类型 —— 任务型清单里的笔记也要算笔记；
     * - 「已完成」只统计 task，笔记没有完成语义。
     */
    data class EntityCounts(
        /** 未完成任务数 */
        val todoTasks: Int,
        /** 笔记数（含已完成状态中的笔记，笔记永远算「进行中」） */
        val notes: Int,
        /** 已完成任务数 */
        val doneTasks: Int,
    ) {
        /** 进行中实体数 = 未完成任务 + 全部笔记 */
        val active: Int get() = todoTasks + notes
    }

    /**
     * 计算当前视图的实体计数。
     *
     * 说明：这里用 derivedStateOf 包住，避免每次重组都递归全表；
     * 只在 tasks 变化时重算一次，列表项内不再做任何统计。
     */
    val entityCounts: EntityCounts by derivedStateOf {
        var todo = 0
        var notes = 0
        var done = 0
        fun walk(list: List<TaskItem>) {
            for (item in list) {
                if (item.isNote) {
                    notes++
                } else if (item.isDone || item.isCompletedForDate) {
                    done++
                } else {
                    todo++
                }
                if (item.subtasks.isNotEmpty()) walk(item.subtasks)
            }
        }
        walk(tasks)
        EntityCounts(todoTasks = todo, notes = notes, doneTasks = done)
    }

    /**
     * 顶部副标题文案（7.4 定稿表格）。
     *
     * 规则：省略为 0 的部分；混合语境始终带单位，避免「3 项」看不出是任务还是笔记。
     */
    val statsSubtitle: String
        get() {
            val c = entityCounts
            return when (activeChecklistId) {
                TODAY_ID -> when {
                    c.todoTasks > 0 && c.notes > 0 -> "今天还有 ${c.todoTasks} 项待办 · ${c.notes} 条笔记"
                    c.todoTasks > 0 -> "今天还有 ${c.todoTasks} 项待办"
                    c.notes > 0 -> "今天有 ${c.notes} 条笔记"
                    c.doneTasks > 0 -> "今天已全部完成，共 ${c.doneTasks} 项"
                    else -> "今天没有安排"
                }
                INBOX_ID -> when {
                    c.todoTasks > 0 && c.notes > 0 -> "${c.todoTasks} 项待整理 · ${c.notes} 条笔记"
                    c.todoTasks > 0 -> "${c.todoTasks} 项待整理"
                    c.notes > 0 -> "${c.notes} 条笔记"
                    else -> "收集箱是空的"
                }
                else -> {
                    // 自建清单：三明治式组合，省略为 0 的部分
                    val parts = buildList {
                        if (c.todoTasks > 0) add("${c.todoTasks} 项待办")
                        if (c.notes > 0) add("${c.notes} 条笔记")
                        if (c.doneTasks > 0) add("${c.doneTasks} 项已完成")
                    }
                    when {
                        parts.isEmpty() -> "暂无内容"
                        // 全部完成且没有待办/笔记时，换成「全部完成，共 N 项」更贴切
                        c.todoTasks == 0 && c.notes == 0 -> "全部完成，共 ${c.doneTasks} 项"
                        else -> parts.joinToString(" · ")
                    }
                }
            }
        }

    /**
     * 空态文案三元组（标题 / 辅助文案 / 主操作提示），按 7.4 表格逐场景给出。
     *
     * @return Triple(空态标题, 空态辅助文案, 主操作提示)
     */
    val emptyStateText: Triple<String, String, String>
        get() {
            val c = entityCounts
            return when (activeChecklistId) {
                TODAY_ID -> Triple("今天没有安排", "留一点空白", "或点击 + 添加任务")
                INBOX_ID -> Triple("收集箱是空的", "", "点击 + 记录一个想法")
                else -> when {
                    // 笔记型清单且只有笔记 → 「还没有笔记」
                    c.notes > 0 -> Triple("从一件小事开始", "", "点击 + 新建任务或笔记")
                    isNotebookView -> Triple("还没有笔记", "", "点击 + 写下第一条笔记")
                    // 混合或任务型清单
                    activeChecklistType == "notebook" -> Triple("从一件小事开始", "", "点击 + 新建任务或笔记")
                    else -> Triple("还没有任务", "", "点击 + 添加第一个任务")
                }
            }
        }

    private var countsJob: Job? = null

    var selectedTaskIds by mutableStateOf<Set<String>>(emptySet())
        private set

    /** 已折叠的主任务 ID 集合，空集=全部展开 */
    var collapsedTaskIds by mutableStateOf<Set<String>>(emptySet())
        private set

    /**
     * 切换指定主任务的折叠状态。
     * 展开→折叠：加入集合；折叠→展开：移出集合。
     */
    fun toggleCollapse(taskId: String) {
        collapsedTaskIds = if (taskId in collapsedTaskIds) {
            collapsedTaskIds - taskId
        } else {
            collapsedTaskIds + taskId
        }
    }

    val isSelectionMode: Boolean by derivedStateOf { selectedTaskIds.isNotEmpty() }

    /**
     * 已启用的智能清单 ID 集合（来自 settings，锁定项强制包含）。
     *
     * 变化时侧栏会立即增减条目；关闭当前所在的非默认智能清单时，
     * 由 [setSmartListEnabled] 负责回退到今天（7.12）。
     */
    var enabledSmartListIds by mutableStateOf(SmartLists.defaultEnabledIds())
        private set


    /** 当前正在执行的刷新协程，用于 cancelPrevious 防止并发刷新导致旧数据覆盖新数据 */
    private var refreshJob: Job? = null

    init {
        // 智能清单启用集合必须先读出来：侧栏与计数都依赖它
        loadSmartListSettings()
        // 初始加载：在协程中执行
        refreshJob = viewModelScope.launch { doRefreshInternal() }

        // 监听 Agent 工具执行后的数据变更事件，自动刷新任务列表
        // debounce(300)：AI 连续调用 N 个工具时，合并为一次刷新（300ms 内的事件只触发最后一次）
        // cancelPrevious：确保同一时刻只有一个刷新协程在运行，避免旧数据覆盖新数据
        viewModelScope.launch {
            app.dataEvents()
                .debounce(300)
                .collect { event ->
                    // 只处理任务和笔记数据变更事件，忽略偏好/记忆等不相关事件
                    if (DataType.TASK_DATA in event.types || DataType.NOTEBOOK_DATA in event.types) {
                        Log.d("TaskViewModel", "收到数据变更事件: ${event.types}")
                        refreshJob?.cancel()
                        refreshJob = viewModelScope.launch { doRefreshInternal() }
                    }
                }
        }
    }

    fun toggleSelection(taskId: String) {
        selectedTaskIds = if (taskId in selectedTaskIds) {
            selectedTaskIds - taskId
        } else {
            selectedTaskIds + taskId
        }
    }

    fun clearSelection() {
        selectedTaskIds = emptySet()
    }

    /**
     * 批量删除选中的任务。
     * 使用递归的 removeTaskFromList 确保任意深度的子任务都能被正确移除，
     * 删除完成后从数据库重新加载任务列表以保证 UI 与数据一致。
     */
    fun deleteSelectedTasks() {
        val ids = selectedTaskIds
        if (ids.isEmpty()) return
        val snapshot = tasks
        // 逐个递归移除选中的任务（支持任意嵌套深度）
        var updated = tasks
        for (id in ids) {
            updated = removeTaskFromList(updated, id)
        }
        tasks = updated
        selectedTaskIds = emptySet()
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    for (id in ids) { core.deleteTask(id) }
                }
                // 从数据库重新加载，确保 UI 与实际数据一致
                tasks = loadTasksForCurrentView()
                scheduleRefreshCounts()
            } catch (e: Exception) {
                onError("批量删除失败: ${e.message}")
                tasks = snapshot
            }
        }
    }

    private fun scheduleRefreshCounts() {
        countsJob?.cancel()
        countsJob = viewModelScope.launch {
            delay(300)
            refreshCounts()
        }
    }

    /**
     * 手动触发刷新（供 UI 操作调用，如切换清单、创建任务后）。
     * 不走防抖，立即执行，同时取消正在进行的旧刷新。
     */
    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch { doRefreshInternal() }
    }

    /**
     * 内部刷新实现 —— 从 Rust 层重新加载清单、分组、任务数据。
     * 所有刷新入口（init debounce、手动 refresh）最终都汇聚到这里，保证串行执行。
     */
    private suspend fun doRefreshInternal() {
        try {
            val loaded = withContext(Dispatchers.IO) {
                core.getChecklists().map { it.toUi() }
            }
            checklists = loaded
        } catch (e: Exception) {
            onError("加载清单失败: ${e.message}")
        }
        try {
            // 刷新当前清单下的分组（"今天"、"收集箱"视图无分组，只有真实清单才有分组）
            groups = if (activeChecklistId != null && activeChecklistId != TODAY_ID && activeChecklistId != INBOX_ID) {
                withContext(Dispatchers.IO) {
                    core.getGroupsByChecklist(activeChecklistId!!).map { it.toUi() }
                }
            } else {
                emptyList()
            }
        } catch (_: Exception) { }
        try {
            val loadedTasks = withContext(Dispatchers.IO) {
                // 智能清单（今天/明天/本周/最近 N 天/收集箱）统一走注册表
                val smartId = activeChecklistId
                if (SmartLists.isSmart(smartId)) {
                    loadSmartList(smartId!!)
                } else {
                    loadTasksWithSubtasks(activeChecklistId, activeGroupId)
                }
            }
            tasks = loadedTasks
        } catch (e: Exception) {
            onError("加载任务失败: ${e.message}")
        }
        refreshCounts()
    }

    /**
     * 切换活跃清单，同时加载该清单下的分组列表，并重置分组筛选。
     * groupId = null 表示显示该清单下所有任务。
     * id = "today" 切换至"今天"视图（跨清单聚合今日任务）。
     * id = "inbox" 切换至"收集箱"视图（未分配清单的孤儿任务）。
     * id = null 切换至"我的任务"（未分配清单的孤儿任务，与 inbox 相同）。
     */
    fun setActiveChecklist(id: String?) {
        viewModelScope.launch {
            try {
                // 切换清单时重置分组选择
                activeGroupId = null
                activeChecklistId = id
                // "今天"和"收集箱"视图不加载分组，真实清单才加载
                groups = if (id != null && id != TODAY_ID && id != INBOX_ID) {
                    withContext(Dispatchers.IO) {
                        core.getGroupsByChecklist(id).map { it.toUi() }
                    }
                } else {
                    emptyList()
                }
                val loadedTasks = withContext(Dispatchers.IO) {
                    if (SmartLists.isSmart(id)) {
                        loadSmartList(id!!)
                    } else {
                        loadTasksWithSubtasks(id, null)
                    }
                }
                tasks = loadedTasks
                scheduleRefreshCounts()
            } catch (e: Exception) {
                onError("切换清单失败: ${e.message}")
            }
        }
    }

    /**
     * 切换活跃分组，筛选显示该分组下的任务。
     * groupId = null 表示显示全部（不按分组过滤）。
     */
    fun setActiveGroup(groupId: String?) {
        viewModelScope.launch {
            try {
                activeGroupId = groupId
                val loadedTasks = withContext(Dispatchers.IO) {
                    loadTasksWithSubtasks(activeChecklistId, groupId)
                }
                tasks = loadedTasks
            } catch (e: Exception) {
                onError("切换分组失败: ${e.message}")
            }
        }
    }

    /**
     * 创建实体（任务或笔记），由 kind 决定类型（7.6 单 FAB + 类型选择器）。
     *
     * 与旧 createTask/createNote 的区别：
     * - 类型显式传入，不再由「当前清单是不是笔记型」推导；
     * - 支持 expected_minutes（预计时长）；
     * - 笔记创建后由调用方打开编辑器，任务创建后回列表。
     *
     * @param kind        "task" 或 "note"
     * @param name        标题
     * @param description 任务描述 / 笔记正文
     * @param priority    优先级（笔记忽略，固定 medium）
     * @param expectedMinutes 预计总工作量（分钟），null = 未规划
     * @param recurrenceRule 循环规则（笔记忽略）
     * @param recurrenceReminderTime 循环提醒时间（笔记忽略）
     * @param reminder    一次性提醒时间（笔记忽略）
     * @param onCreated   创建成功回调，传入新实体 ID
     */
    fun createEntity(
        kind: String,
        name: String,
        description: String?,
        priority: String,
        expectedMinutes: Long? = null,
        recurrenceRule: String? = null,
        recurrenceReminderTime: String? = null,
        reminder: String? = null,
        onCreated: ((String) -> Unit) = {},
    ) {
        viewModelScope.launch {
            try {
                // "今天"和"收集箱"不是真实清单，创建时 category_id 设为 null
                val realCategoryId = when (activeChecklistId) {
                    TODAY_ID, INBOX_ID -> null
                    else -> activeChecklistId
                }
                // 在"今天"视图中创建任务时，若未指定 reminder，自动设为今天日期，
                // 否则新任务不会出现在当前列表里（用户会以为没创建成功）。
                // 笔记不参与时间视图，不做这个兜底。
                val isTask = kind != "note"
                val finalReminder = if (isTask && reminder == null && activeChecklistId == TODAY_ID) {
                    java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
                } else {
                    reminder
                }
                val created = withContext(Dispatchers.IO) {
                    core.createTaskEx(
                        name = name,
                        description = description,
                        priority = if (isTask) priority else "medium",
                        categoryId = realCategoryId,
                        parentId = null,
                        groupId = activeGroupId,
                        recurrenceRule = if (isTask) recurrenceRule else null,
                        recurrenceReminderTime = if (isTask) recurrenceReminderTime else null,
                        reminder = finalReminder,
                        kind = kind,
                        expectedMinutes = expectedMinutes,
                    )
                }
                // 只有任务需要调度提醒闹钟
                if (isTask) scheduleReminderIfNeeded(created)
                tasks = loadTasksForCurrentView()
                scheduleRefreshCounts()
                onCreated(created.id)
            } catch (e: Exception) {
                onError("创建失败: ${e.message}")
            }
        }
    }

    /**
     * 创建任务。自动关联到当前活跃清单和活跃分组。
     * 在"今天"或"收集箱"视图中创建时，category_id 为 null（不属于任何清单），
     * 任务会自动出现在"收集箱"中。
     *
     * @param name 任务名称
     * @param description 任务描述，可为 null
     * @param priority 优先级："high" / "medium" / "low"
     * @param recurrenceRule 循环规则：null/"none" 不循环，"daily" 每日循环
     * @param recurrenceReminderTime 每日循环提醒时间，格式 "HH:MM"
     * @param reminder 一次性提醒时间（ISO 格式），为 null 时在"今天"视图会自动设为今天日期
     * @param onCreated 任务创建成功后的回调，传入新任务 ID
     * @return 新创建的任务 ID，失败时返回 null
     */
    fun createTask(
        name: String,
        description: String?,
        priority: String,
        recurrenceRule: String? = null,
        recurrenceReminderTime: String? = null,
        reminder: String? = null,
        onCreated: ((String) -> Unit) = {},
    ) {
        viewModelScope.launch {
            try {
                // "今天"和"收集箱"不是真实清单，创建任务时 category_id 设为 null
                val realCategoryId = when (activeChecklistId) {
                    TODAY_ID, INBOX_ID -> null
                    else -> activeChecklistId
                }
                // 在"今天"视图中，若未指定 reminder，自动设为今天日期，使任务出现在今天列表
                val finalReminder = if (reminder == null && activeChecklistId == TODAY_ID) {
                    java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
                } else {
                    reminder
                }
                val newTask = withContext(Dispatchers.IO) {
                    core.createTask(name, description, priority, realCategoryId, null, activeGroupId, recurrenceRule, recurrenceReminderTime, finalReminder)
                }
                // 创建成功后调度提醒闹钟
                scheduleReminderIfNeeded(newTask)
                tasks = loadTasksForCurrentView()
                scheduleRefreshCounts()
                onCreated(newTask.id)
            } catch (e: Exception) {
                onError("创建任务失败: ${e.message}")
            }
        }
    }

    /**
     * 在当前笔记型清单中创建一条新笔记（带标题和初始内容）。
     * 笔记复用 TaskData 结构：name = 标题，description = Markdown 正文。
     * 创建成功后通过 onCreated 回调传入新笔记的 ID。
     *
     * @param title 笔记标题
     * @param content 笔记初始 Markdown 正文（可为空）
     * @param onCreated 创建成功回调，传入新笔记 ID
     */
    fun createNote(title: String, content: String? = null, onCreated: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val newNote = withContext(Dispatchers.IO) {
                    core.createTask(
                        title.ifBlank { "新笔记" },
                        content?.ifBlank { null },
                        "medium",
                        activeChecklistId,
                        null, activeGroupId, null, null, null,
                    )
                }
                tasks = loadTasksForCurrentView()
                scheduleRefreshCounts()
                onCreated(newNote.id)
            } catch (e: Exception) {
                onError("创建笔记失败: ${e.message}")
            }
        }
    }

    /**
     * 创建子任务。继承父任务所在的清单和分组。
     */
    fun createSubtask(parentId: String, name: String, priority: String = "medium") {
        viewModelScope.launch {
            try {
                // 子任务继承父任务的分组归属
                val parentGroup = activeGroupId
                // "今天"和"收集箱"不是真实清单，子任务 category_id 设为 null
                val realCategoryId = when (activeChecklistId) {
                    TODAY_ID, INBOX_ID -> null
                    else -> activeChecklistId
                }
                withContext(Dispatchers.IO) {
                    core.createTask(name, null, priority, realCategoryId, parentId, parentGroup, null, null, null)
                }
                tasks = loadTasksForCurrentView()
                scheduleRefreshCounts()
            } catch (e: Exception) {
                onError("创建子任务失败: ${e.message}")
            }
        }
    }

    /**
     * 切换任务完成状态。
     * 每日任务：使用实例化 API（status 变 done + 自动创建/删除明天实例）
     * 普通任务：改 tasks.status 为 "done"/"todo"
     */
    fun toggleDone(task: TaskItem) {
        if (task.isDaily) {
            toggleDailyDone(task)
        } else {
            toggleNormalDone(task)
        }
    }

    /**
     * 每日任务的完成/取消（新模型：实例化）。
     * 完成：调用 completeDailyTaskInstance → status 变 done + 自动创建明天的实例
     * 取消：调用 uncompleteDailyTaskInstance → status 恢复 todo + 自动删除明天实例
     *
     * "今天"视图下不做全量刷新：completeDailyTaskInstance 创建的"明天"实例不会出现在
     * getTasksDueToday 查询结果中，乐观更新已经是正确状态。跳过刷新可以避免替换整个
     * tasks 列表导致 animateItem 位移动画被打断（首次完成时 done 区从无到有，LazyColumn
     * 布局重建会重置 animateItem 的内部位置追踪）。
     * 非"今天"视图仍然全量刷新，因为明天的新实例可能影响其他清单的显示。
     */
    private fun toggleDailyDone(task: TaskItem) {
        val markDone = !task.isCompletedForDate
        // 乐观更新 UI（立即生效，启动 animateItem 位移动画）
        val updatedTask = task.copy(isDone = markDone, isCompletedForDate = markDone)
        tasks = updateTaskInList(tasks, task.id, updatedTask)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (markDone) {
                        // 完成并自动创建明天的实例
                        val result = core.completeDailyTaskInstance(task.id)
                        // 取消今天的提醒闹钟
                        ReminderStore.resetTriggerCount(app, task.id)
                        ReminderScheduler.cancelReminder(app, task.id)
                        NotificationHelper.dismissNotification(app, task.id)
                        // 为自动生成的明天实例注册提醒闹钟
                        result.newTask?.let { newTask ->
                            scheduleReminderIfNeeded(newTask)
                            Log.d("TaskViewModel", "已为新每日实例注册提醒: ${newTask.id}")
                        }
                    } else {
                        // 取消完成并删除明天的实例
                        // uncomplete 会删除明天自动创建的实例，需取消其闹钟
                        val result = core.uncompleteDailyTaskInstance(task.id)
                        // 恢复原任务的提醒闹钟
                        scheduleReminderIfNeeded(result)
                        Log.d("TaskViewModel", "已恢复每日任务提醒: ${result.id}")
                    }
                }
                // "今天"视图：跳过全量刷新，避免打断 animateItem 动画。
                // 乐观更新已经是正确状态（明天的新实例不会出现在今天的查询结果中）。
                // 非今天视图：需要全量刷新，因为新建/删除了明天的任务可能影响当前清单。
                if (activeChecklistId != TODAY_ID) {
                    tasks = loadTasksForCurrentView()
                }
                scheduleRefreshCounts()
            } catch (e: Exception) {
                onError("更新失败: ${e.message}")
                // 错误时始终全量刷新，恢复乐观更新前的真实状态
                tasks = loadTasksForCurrentView()
            }
        }
    }

    /**
     * 普通任务的完成/取消：改 tasks.status + 级联子任务。
     */
    private fun toggleNormalDone(task: TaskItem) {
        val markDone = !task.isDone
        val newStatus = if (markDone) "done" else "todo"
        val updatedTask = if (markDone) markAllDone(task) else markAllTodo(task)
        tasks = updateTaskInList(tasks, task.id, updatedTask)
        // 标记完成时取消该任务的提醒闹钟和循环
        if (markDone) {
            ReminderStore.resetTriggerCount(app, task.id)
            ReminderScheduler.cancelReminder(app, task.id)
            NotificationHelper.dismissNotification(app, task.id)
        }
        viewModelScope.launch {
            try {
                val allIds = collectIds(updatedTask)
                withContext(Dispatchers.IO) {
                    for (id in allIds) {
                        core.updateTask(id, null, null, null, newStatus, null, null, null, null, null)
                    }
                }
            } catch (e: Exception) {
                onError("更新失败: ${e.message}")
                tasks = loadTasksForCurrentView()
            }
        }
    }

    /**
     * 更新任务的预计总工作量（分钟）。
     *
     * 与「专注计时」无关：expected_minutes 只表达预计投入，不会自动开始计时、
     * 也不产生结束时间字段（点任务原则）。
     *
     * @param id      实体 ID
     * @param minutes 预计分钟数；null 表示清除
     */
    fun updateExpectedMinutes(id: String, minutes: Long?) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    core.updateTaskFull(
                        id = id,
                        name = null, description = null, priority = null, status = null,
                        kind = null, expectedMinutes = minutes,
                        // 传 null 时用清除标志位，否则 update_task_full 会把它当成「不修改」
                        clearExpectedMinutes = minutes == null,
                        categoryId = null,
                        setGroup = false, groupId = null,
                        setParent = false, parentId = null,
                        reminder = null, clearReminder = false,
                        recurrenceRule = null, recurrenceReminderTime = null,
                    )
                }
                tasks = loadTasksForCurrentView()
            } catch (e: Exception) {
                onError("保存预计时长失败: ${e.message}")
            }
        }
    }

    /**
     * 实体类型互转（task ↔ note）。
     *
     * 互转只改 kind：内容、关联、专注记录、附件全部跟随实体保留（7.2）。
     * task→note 时 completed_at 保留，转回 task 后能恢复已完成状态显示。
     *
     * @param id      实体 ID
     * @param newKind 目标类型 "task" / "note"
     */
    fun switchKind(id: String, newKind: String) {
        val snapshot = tasks
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    core.updateTaskFull(
                        id = id,
                        name = null, description = null, priority = null, status = null,
                        kind = newKind, expectedMinutes = null, clearExpectedMinutes = false,
                        categoryId = null,
                        setGroup = false, groupId = null,
                        setParent = false, parentId = null,
                        reminder = null, clearReminder = false,
                        recurrenceRule = null, recurrenceReminderTime = null,
                    )
                }
                tasks = loadTasksForCurrentView()
                scheduleRefreshCounts()
            } catch (e: Exception) {
                // 乐观更新失败回滚：不改实体但列表已重载，确保 UI 与库一致
                tasks = snapshot
                onError("转换失败: ${e.message}")
            }
        }
    }

    /**
     * 移动实体到目标清单（可选同时改分组）。
     *
     * 一次 update_task_full 调用提交，避免「清单改了、分组没改」的中间态。
     * 目标清单没有对应分组时调用方应传 null，让实体落到「未分组」。
     *
     * @param id          实体 ID
     * @param checklistId 目标清单 ID
     * @param groupId     目标分组 ID；null = 未分组
     */
    fun moveEntity(id: String, checklistId: String, groupId: String?) {
        val snapshot = tasks
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    core.updateTaskFull(
                        id = id,
                        name = null, description = null, priority = null, status = null,
                        kind = null, expectedMinutes = null, clearExpectedMinutes = false,
                        categoryId = checklistId,
                        // setGroup=true + groupId=null 表示「清除分组」，与「不改分组」区分开
                        setGroup = true, groupId = groupId,
                        setParent = false, parentId = null,
                        reminder = null, clearReminder = false,
                        recurrenceRule = null, recurrenceReminderTime = null,
                    )
                }
                tasks = loadTasksForCurrentView()
                scheduleRefreshCounts()
            } catch (e: Exception) {
                tasks = snapshot
                onError("未保存：${e.message}")
            }
        }
    }

    /**
     * 删除实体并让它被删除的直接子项上移一级（7.7）。
     *
     * 原子性由 core 事务保证：删除、子项改挂、关联清理要么全成要么全不发生。
     * 失败时回滚列表快照并提示重试，不把失败包装成成功。
     *
     * @param id 实体 ID
     */
    fun deleteEntity(id: String) {
        val snapshot = tasks
        tasks = removeTaskFromList(tasks, id)
        ReminderScheduler.cancelReminder(app, id)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { core.deleteTask(id) }
                tasks = loadTasksForCurrentView()
                scheduleRefreshCounts()
            } catch (e: Exception) {
                tasks = snapshot
                onError("删除失败，请重试：${e.message}")
            }
        }
    }

    /**
     * 更新任务字段（标题、描述、优先级）。
     * 所有参数均为可选，仅非 null 参数会被更新。
     */
    fun updateTask(id: String, name: String? = null, description: String? = null, priority: String? = null) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    core.updateTask(id, name, description, priority, null, null, null, null, null, null)
                }
                tasks = loadTasksForCurrentView()
                scheduleRefreshCounts()
            } catch (e: Exception) {
                onError("更新任务失败: ${e.message}")
            }
        }
    }

    /**
     * 更新任务的每日循环设置。
     *
     * @param id 任务 ID
     * @param recurrenceRule 循环规则：null/"none" 不循环，"daily" 每日循环
     * @param reminderTime 提醒时间，格式 "HH:MM"
     */
    fun updateRecurrence(id: String, recurrenceRule: String?, reminderTime: String?) {
        viewModelScope.launch {
            try {
                val updated = withContext(Dispatchers.IO) {
                    core.setTaskRecurrence(id, recurrenceRule, reminderTime)
                }
                // 更新循环设置后重新调度提醒闹钟
                scheduleReminderIfNeeded(updated)
                tasks = loadTasksForCurrentView()
            } catch (e: Exception) {
                onError("更新循环失败: ${e.message}")
            }
        }
    }

    /**
     * 移除任务的每日循环（将 recurrence_rule 和 recurrence_reminder_time 设为 NULL）。
     */
    fun removeRecurrence(id: String) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    core.removeTaskRecurrence(id)
                }
                // 移除循环后取消每日闹钟
                ReminderScheduler.cancelReminder(app, id)
                tasks = loadTasksForCurrentView()
            } catch (e: Exception) {
                onError("移除循环失败: ${e.message}")
            }
        }
    }

    fun deleteTask(id: String) {
        val snapshot = tasks
        tasks = removeTaskFromList(tasks, id)
        // 删除任务时取消提醒闹钟
        ReminderScheduler.cancelReminder(app, id)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { core.deleteTask(id) }
                scheduleRefreshCounts()
            } catch (e: Exception) {
                onError("删除失败: ${e.message}")
                tasks = snapshot
            }
        }
    }

    fun createChecklist(name: String) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { core.createChecklist(name) }
                val loaded = withContext(Dispatchers.IO) { core.getChecklists().map { it.toUi() } }
                checklists = loaded
                scheduleRefreshCounts()
            } catch (e: Exception) {
                onError("创建清单失败: ${e.message}")
            }
        }
    }

    /**
     * 创建指定类型的清单。
     * @param name 清单名称
     * @param checklistType "task" 或 "notebook"
     */
    fun createChecklistWithType(name: String, checklistType: String) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { core.createChecklistWithType(name, checklistType) }
                val loaded = withContext(Dispatchers.IO) { core.getChecklists().map { it.toUi() } }
                checklists = loaded
                scheduleRefreshCounts()
            } catch (e: Exception) {
                onError("创建清单失败: ${e.message}")
            }
        }
    }

    fun deleteChecklist(id: String) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { core.deleteChecklist(id) }
                if (activeChecklistId == id) {
                    // 删掉当前清单：回退到今天（智能清单一定存在且默认启用）
                    activeChecklistId = TODAY_ID
                    tasks = withContext(Dispatchers.IO) { loadSmartList(SmartLists.TODAY) }
                }
                checklists = withContext(Dispatchers.IO) { core.getChecklists().map { it.toUi() } }
                scheduleRefreshCounts()
            } catch (e: Exception) {
                onError("删除清单失败: ${e.message}")
            }
        }
    }

    fun moveAndReorderTasks(taskId: String, newParentId: String?, siblingIds: List<String>) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    core.updateTaskParent(taskId, newParentId)
                    if (siblingIds.size > 1) {
                        core.reorderTasks(siblingIds)
                    }
                }
                tasks = loadTasksForCurrentView()
                scheduleRefreshCounts()
            } catch (e: Exception) {
                onError("移动任务失败: ${e.message}")
            }
        }
    }

    fun moveTask(taskId: String, newParentId: String?) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    core.updateTaskParent(taskId, newParentId)
                }
                tasks = loadTasksForCurrentView()
                scheduleRefreshCounts()
            } catch (e: Exception) {
                onError("移动任务失败: ${e.message}")
            }
        }
    }

    fun reorderTasks(orderedIds: List<String>) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { core.reorderTasks(orderedIds) }
                tasks = loadTasksForCurrentView()
            } catch (e: Exception) {
                onError("排序失败: ${e.message}")
            }
        }
    }

    fun reorderChecklists(orderedIds: List<String>) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { core.reorderChecklists(orderedIds) }
                // 重新从数据库加载清单列表，刷新侧边栏顺序
                checklists = withContext(Dispatchers.IO) { core.getChecklists().map { it.toUi() } }
            } catch (e: Exception) {
                onError("清单排序失败: ${e.message}")
            }
        }
    }

    private suspend fun refreshCounts() {
        val currentChecklists = checklists
        val currentActiveId = activeChecklistId
        val currentTasks = tasks
        val counts = withContext(Dispatchers.IO) {
            val result = mutableMapOf<String?, Pair<Int, Int>>()
            // 当前视图的计数（可能是智能清单 ID、null 或真实清单 ID）
            result[currentActiveId] = countItems(currentTasks)
            // 侧栏里可见的智能清单逐个计数（注册表遍历，不再硬编码「今天」「收集箱」）。
            // 未启用的不计算，省掉无用的跨表查询。
            for (def in SmartLists.visible(enabledSmartListIds)) {
                if (def.id == currentActiveId) continue
                result[def.id] = countItems(loadSmartList(def.id))
            }
            // 收集箱同时以 null 作为键，兼容旧的「我的任务」视图读取
            result[INBOX_ID]?.let { result[null] = it }
            if (result[null] == null && currentActiveId != INBOX_ID) {
                result[null] = countItems(loadTasksWithSubtasks(null, null))
            }
            // 各真实清单计数
            for (cl in currentChecklists) {
                if (cl.id != currentActiveId) {
                    result[cl.id] = countItems(loadTasksWithSubtasks(cl.id, null))
                }
            }
            result.toMap()
        }
        checklistCounts = counts
    }

    private fun countItems(items: List<TaskItem>): Pair<Int, Int> {
        var taskCount = 0
        var subtaskCount = 0
        fun walk(items: List<TaskItem>, depth: Int) {
            for (item in items) {
                // 已完成的任务不计入统计，只显示未完成数量
                if (item.isDone) continue
                if (depth == 0) taskCount++ else subtaskCount++
                walk(item.subtasks, depth + 1)
            }
        }
        walk(items, 0)
        return Pair(taskCount, subtaskCount)
    }

    /**
     * 加载指定清单和分组下的任务树。
     * categoryId = null 表示"我的任务"；groupId = null 表示不按分组过滤。
     */
    private fun loadTasksWithSubtasks(categoryId: String?, groupId: String?): List<TaskItem> {
        fun loadChildren(parentId: String): List<TaskItem> {
            return core.getSubtasks(parentId).map { task ->
                val subs = loadChildren(task.id)
                task.toUi().copy(subtasks = subs)
            }
        }
        return core.getTasksByCategory(categoryId, groupId).map { task ->
            val subs = loadChildren(task.id)
            task.toUi().copy(subtasks = subs)
        }
    }

    /** 侧栏要显示的智能清单（注册表顺序，已被启用集合过滤） */
    val visibleSmartLists: List<SmartListDef>
        get() = SmartLists.visible(enabledSmartListIds)

    /** 从 settings 读取智能清单启用集合；缺失或损坏时回默认值 */
    fun loadSmartListSettings() {
        try {
            val raw = core.getSetting(SETTING_SMART_LISTS)
            val stored = if (raw.isNullOrBlank()) {
                SmartLists.defaultEnabledIds()
            } else {
                raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            }
            // 锁定项强制启用，避免设置损坏导致空侧栏
            enabledSmartListIds = SmartLists.effectiveEnabledIds(stored)
        } catch (_: Exception) {
            enabledSmartListIds = SmartLists.defaultEnabledIds()
        }
    }

    /**
     * 启用/停用某个智能清单。
     *
     * 锁定项（今天、收集箱）直接忽略请求 —— 界面上也不给 Switch，
     * 这里再兜一层，防止通过其它路径把侧栏关空。
     *
     * @param id      智能清单 ID
     * @param enabled 目标状态
     * @param onSwitchedAway 关闭当前所在清单并回退到今天的回调（用于提示用户）
     */
    fun setSmartListEnabled(id: String, enabled: Boolean, onSwitchedAway: (() -> Unit)? = null) {
        val def = SmartLists.find(id) ?: return
        if (def.locked) return
        val next = if (enabled) enabledSmartListIds + id else enabledSmartListIds - id
        enabledSmartListIds = SmartLists.effectiveEnabledIds(next)
        try {
            core.setSetting(SETTING_SMART_LISTS, enabledSmartListIds.joinToString(","))
        } catch (e: Exception) {
            onError("保存智能清单设置失败: ${e.message}")
        }
        // 关闭的正是当前所在清单：先回退到今天，再移除，避免停留在「已隐藏的视图」
        if (!enabled && activeChecklistId == id) {
            setActiveChecklist(TODAY_ID)
            onSwitchedAway?.invoke()
        }
    }

    /**
     * 按注册表口径加载某个智能清单。
     *
     * 与旧 loadTodayTasks 的差异：口径统一由 [SmartLists.aggregate] 决定，
     * 这里只负责「取顶层实体 → 过滤 → 递归挂子项」的通用流程。
     *
     * @param smartListId 智能清单 ID
     */
    private fun loadSmartList(smartListId: String): List<TaskItem> {
        val today = java.time.LocalDate.now()
        fun loadChildren(parentId: String): List<TaskItem> {
            return core.getSubtasks(parentId).map { task ->
                val subs = loadChildren(task.id)
                task.toUi().copy(subtasks = subs)
            }
        }
        // 顶层实体：先取全量再按注册表过滤（实体数量级为个人清单，
        // 全量取一次比按清单逐个查询再合并更简单，也避免多次跨清单 I/O）
        val allTop = core.getTasks().filter { it.parentId == null }
        return SmartLists.aggregate(smartListId, today, allTop.map { it.toUi() })
            .map { item ->
                // 需要原始 TaskData 才能递归查子项；这里用已映射的 UI 模型 ID 查
                val subs = loadChildren(item.id)
                item.copy(subtasks = subs)
            }
    }


    /**
     * 根据当前活跃视图加载任务数据。
     * "today" → 今日聚合查询；
     * "inbox" → 孤儿任务查询（category_id = null）；
     * 其他 → 指定清单查询。
     */
    private suspend fun loadTasksForCurrentView(): List<TaskItem> = withContext(Dispatchers.IO) {
        // 智能清单统一走注册表；真实清单走原来的清单查询
        val smartId = activeChecklistId
        if (SmartLists.isSmart(smartId)) {
            loadSmartList(smartId!!)
        } else {
            loadTasksWithSubtasks(activeChecklistId, activeGroupId)
        }
    }

    // ==================== 分组操作 ====================

    /**
     * 创建分组，创建后刷新分组列表。
     * @param name 分组名称（如"语文"）
     * @param color 可选的颜色标识（如"#FF5722"）
     */
    fun createGroup(name: String, color: String? = null) {
        val checklistId = activeChecklistId ?: return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    core.createGroup(name, checklistId, color)
                }
                groups = withContext(Dispatchers.IO) {
                    core.getGroupsByChecklist(checklistId).map { it.toUi() }
                }
            } catch (e: Exception) {
                onError("创建分组失败: ${e.message}")
            }
        }
    }

    /**
     * 删除分组，保留组内任务（group_id 被置空）。
     */
    fun deleteGroup(groupId: String) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { core.deleteGroup(groupId) }
                groups = withContext(Dispatchers.IO) {
                    activeChecklistId?.let { core.getGroupsByChecklist(it).map { g -> g.toUi() } } ?: emptyList()
                }
                // 如果删除的是当前选中分组，切回"全部"
                if (activeGroupId == groupId) {
                    activeGroupId = null
                }
                tasks = loadTasksForCurrentView()
                scheduleRefreshCounts()
            } catch (e: Exception) {
                onError("删除分组失败: ${e.message}")
            }
        }
    }

    /**
     * 重命名分组。
     */
    fun renameGroup(groupId: String, newName: String) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { core.updateGroup(groupId, newName, null) }
                groups = withContext(Dispatchers.IO) {
                    activeChecklistId?.let { core.getGroupsByChecklist(it).map { g -> g.toUi() } } ?: emptyList()
                }
            } catch (e: Exception) {
                onError("重命名分组失败: ${e.message}")
            }
        }
    }

    // ==================== 附件管理 ====================

    /**
     * 获取指定任务的所有附件，返回 UI 模型列表。
     * 调用方需在协程中调用。
     */
    suspend fun getAttachments(taskId: String): List<AttachmentUiItem> {
        return withContext(Dispatchers.IO) {
            core.getAttachments(taskId).map { it.toUi() }
        }
    }

    /**
     * 为任务添加附件。
     * 文件应已复制到内部存储，此方法将附件记录写入数据库。
     *
     * @param taskId 任务 ID
     * @param filePath 内部存储文件路径
     * @param fileName 原始文件名
     * @param mimeType MIME 类型
     * @param fileSize 文件大小（字节）
     */
    fun addAttachment(
        taskId: String,
        filePath: String,
        fileName: String,
        mimeType: String,
        fileSize: Long,
    ) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    core.addAttachment(taskId, fileName, filePath, mimeType, fileSize)
                }
            } catch (e: Exception) {
                onError("添加附件失败: ${e.message}")
            }
        }
    }

    /**
     * 删除附件。
     *
     * @param attachmentId 附件 ID
     */
    fun removeAttachment(attachmentId: String) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    core.removeAttachment(attachmentId)
                }
            } catch (e: Exception) {
                onError("删除附件失败: ${e.message}")
            }
        }
    }

    /**
     * 批量关联临时附件到新创建的任务。
     * 用于新建任务场景：附件先存在临时列表中，任务创建后再批量写入数据库。
     *
     * @param taskId 新创建的任务 ID
     * @param pendingAttachments 临时附件信息列表
     */
    fun commitPendingAttachments(
        taskId: String,
        pendingAttachments: List<PickedFileInfo>,
    ) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    for (info in pendingAttachments) {
                        core.addAttachment(taskId, info.fileName, info.filePath, info.mimeType, info.fileSize)
                    }
                }
            } catch (e: Exception) {
                onError("关联附件失败: ${e.message}")
            }
        }
    }

    // ==================== 提醒调度 ====================

    /**
     * 根据任务数据调度提醒闹钟。
     *
     * 处理两种提醒：
     * - 每日循环任务（recurrenceRule="daily"）：调度每日重复闹钟
     * - 普通任务（reminder 字段有值）：调度一次性精确闹钟
     * 如果任务不需要提醒，取消已有闹钟。
     */
    private fun scheduleReminderIfNeeded(task: TaskData) {
        // 先取消已有闹钟，避免重复
        ReminderScheduler.cancelReminder(app, task.id)

        // 每日循环任务：解析 HH:MM 并调度每日闹钟
        if (task.recurrenceRule == "daily" && task.recurrenceReminderTime != null) {
            val parts = task.recurrenceReminderTime!!.split(":")
            if (parts.size == 2) {
                val hour = parts[0].toIntOrNull()
                val minute = parts[1].toIntOrNull()
                if (hour != null && minute != null) {
                    ReminderScheduler.scheduleDailyReminder(app, task.id, hour, minute)
                }
            }
        }

        // 普通任务：解析 reminder 时间戳并调度一次性闹钟
        if (task.reminder != null) {
            try {
                // reminder 格式可能是 "YYYY-MM-DDTHH:MM" 或 RFC 3339
                val formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
                val ldt = java.time.LocalDateTime.parse(task.reminder, formatter)
                val millis = ldt.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                if (millis > System.currentTimeMillis()) {
                    ReminderScheduler.scheduleExactReminder(app, task.id, millis)
                }
            } catch (_: Exception) {
                // 解析失败静默忽略
            }
        }
    }

    /**
     * 更新任务的一次性提醒时间。
     * 同时调度/取消对应的闹钟。
     *
     * @param id 任务 ID
     * @param reminder 提醒时间字符串（"YYYY-MM-DDTHH:MM"），null 表示清除提醒
     */
    fun updateReminder(id: String, reminder: String?) {
        viewModelScope.launch {
            try {
                val updated = withContext(Dispatchers.IO) {
                    // null 表示清除提醒，需要调用 clearTaskReminder 将 reminder 字段设为 NULL；
                    // 非 null 表示设置新的提醒时间，走 updateTask 正常更新
                    if (reminder == null) {
                        core.clearTaskReminder(id)
                    } else {
                        core.updateTask(id, null, null, null, null, null, reminder, null, null, null)
                    }
                }
                // 更新提醒后重新调度闹钟
                scheduleReminderIfNeeded(updated)
                tasks = loadTasksForCurrentView()
            } catch (e: Exception) {
                onError("更新提醒失败: ${e.message}")
            }
        }
    }

    // ==================== 笔记-任务关联 ====================

    /** 缓存的关联笔记列表（在任务详情页中使用） */
    var linkedNotes by mutableStateOf<List<TaskItem>>(emptyList())
        private set

    /** 缓存的关联任务列表（在笔记编辑器中使用） */
    var linkedTasks by mutableStateOf<List<TaskItem>>(emptyList())
        private set

    /**
     * 读取指定清单下的分组（不改变当前活跃分组状态）。
     *
     * 用于「移动实体」菜单：目标清单可能不是当前清单，
     * 需要一份独立的、不影响主列表的临时分组列表。
     *
     * @param checklistId 清单 ID
     * @return 分组 UI 模型列表；读取失败返回空列表（调用方按「未分组」处理）
     */
    suspend fun groupsOf(checklistId: String): List<GroupItem> {
        return try {
            withContext(Dispatchers.IO) { core.getGroupsByChecklist(checklistId).map { it.toUi() } }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * 加载指定任务关联的所有笔记。
     * @param taskId 任务 ID
     */
    fun loadLinkedNotes(taskId: String) {
        viewModelScope.launch {
            try {
                val notes = withContext(Dispatchers.IO) {
                    core.getLinkedNotes(taskId).map { it.toUi() }
                }
                linkedNotes = notes
            } catch (e: Exception) {
                linkedNotes = emptyList()
            }
        }
    }

    /**
     * 加载指定笔记关联的所有任务。
     * @param noteId 笔记 ID
     */
    fun loadLinkedTasks(noteId: String) {
        viewModelScope.launch {
            try {
                val tasks = withContext(Dispatchers.IO) {
                    core.getLinkedTasks(noteId).map { it.toUi() }
                }
                linkedTasks = tasks
            } catch (e: Exception) {
                linkedTasks = emptyList()
            }
        }
    }

    /**
     * 关联笔记和任务（双向写入）。
     * @param noteId 笔记 ID
     * @param taskId 任务 ID
     */
    fun linkNoteToTask(noteId: String, taskId: String) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { core.linkNoteToTask(noteId, taskId) }
                // 刷新关联列表
                loadLinkedTasks(noteId)
                loadLinkedNotes(taskId)
            } catch (e: Exception) {
                onError("关联失败: ${e.message}")
            }
        }
    }

    /**
     * 取消笔记和任务的关联（双向删除）。
     * @param noteId 笔记 ID
     * @param taskId 任务 ID
     */
    fun unlinkNoteFromTask(noteId: String, taskId: String) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { core.unlinkNoteFromTask(noteId, taskId) }
                loadLinkedTasks(noteId)
                loadLinkedNotes(taskId)
            } catch (e: Exception) {
                onError("取消关联失败: ${e.message}")
            }
        }
    }

    /**
     * 按 ID 查实体（任务或笔记），用于「从通知/聊天跳转到某实体」的定位。
     *
     * 为什么不只在内存列表里找：目标实体可能不在当前清单视图内
     * （例如用户停在「今天」，通知指向工作清单里的任务）。直接查库能拿到真实归属
     * （categoryId），调用方据此先切清单再展开，避免打开空白浮层。
     *
     * @param id 实体 ID
     * @return 实体数据；不存在（已被删除）时返回 null
     */
    suspend fun findEntityById(id: String): TaskData? {
        return try {
            withContext(Dispatchers.IO) { core.getTask(id) }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 全局搜索所有笔记型清单中的笔记。
     * @param query 搜索关键词
     * @return 匹配的笔记列表
     */
    suspend fun searchNotesGlobal(query: String): List<TaskItem> {
        return try {
            withContext(Dispatchers.IO) {
                // includeChildren=false：UI 搜索保持文档式语义（只搜根笔记）
                core.searchNotes(query, null, false).map { it.toUi() }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * 关联选择器用的笔记列表（同步快照）。
     *
     * 与 [getAllNotes] 的区别：这里直接同步返回已加载的清单下的笔记，
     * 供 Compose 在对话框打开时按需取用；仍按实体 kind=note 过滤，
     * 而不是按清单类型 —— 任务型清单里的笔记也要能被关联。
     */
    suspend fun allNotesForLink(): List<TaskItem> = getAllNotes()

    /**
     * 获取所有笔记型清单中的笔记（用于关联选择器）。
     * @return 所有笔记列表
     */
    suspend fun getAllNotes(): List<TaskItem> {
        return try {
            withContext(Dispatchers.IO) {
                // 按实体 kind 枚举笔记（不再按清单类型推导）：
                // 任务型清单里的笔记、收集箱里的笔记都能被关联到。
                // include_children=true 让子笔记也进入候选。
                core.searchNotes(null, null, true).map { it.toUi() }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * 获取所有任务型清单中的任务（用于关联选择器）。
     * @return 所有顶层任务列表
     */
    suspend fun getAllTasks(): List<TaskItem> {
        return try {
            withContext(Dispatchers.IO) {
                // 按实体 kind 过滤任务（与 getAllNotes 对称），避免漏掉笔记型清单里的任务
                core.getTasks().filter { it.kind != "note" }.map { it.toUi() }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    companion object {
        /**
         * "今天"视图的虚拟清单 ID。
         * 不是真实存储在 DB 中的清单，而是 App 启动默认显示的跨清单聚合视图。
         */
        const val TODAY_ID = "today"

        /**
         * "收集箱"视图的虚拟清单 ID。
         * 不是真实存储在 DB 中的清单，用于显示所有未分配清单的孤儿任务（category_id = null）。
         * 任何没有关联清单的任务都会自动出现在此视图中。
         */
        const val INBOX_ID = "inbox"

        /** settings 键：已启用的智能清单 ID（逗号分隔） */
        const val SETTING_SMART_LISTS = "smart_lists_enabled"
    }
}

@Composable
fun taskViewModel(): TaskViewModel {
    val context = LocalContext.current
    val app = context.applicationContext as Application
    return viewModel(
        factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return TaskViewModel(app.core(), { msg ->
                    Toast.makeText(app, msg, Toast.LENGTH_SHORT).show()
                }, app) as T
            }
        }
    )
}
