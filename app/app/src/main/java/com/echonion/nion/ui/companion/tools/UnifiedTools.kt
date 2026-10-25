package com.echonion.nion.ui.companion.tools

import com.echonion.nion.NionApp
import com.echonion.nion.ui.companion.weather.FullWeatherData
import com.echonion.nion.ui.companion.weather.WeatherService
import com.echonion.nion.ui.companion.weather.aqiDescription
import com.echonion.nion.ui.companion.weather.weatherDescription
import org.json.JSONArray
import org.json.JSONObject
import uniffi.nion_core.ChecklistData
import uniffi.nion_core.GroupData
import uniffi.nion_core.NionCore
import uniffi.nion_core.TaskData

// ═══════════════════════════════════════════════════════════════════
// JSON 序列化辅助函数
// 将 UniFFI Record 转换为 JSON，供 LLM 理解工具返回值
// ═══════════════════════════════════════════════════════════════════

/**
 * 将 [TaskData] 序列化为 JSON 对象。
 * 只输出非空字段，跳过 null 值和默认值以减少 token 消耗。
 *
 * reminder 字段合并规则（反向）：
 * - 如果 recurrenceRule == "daily" 且 recurrenceReminderTime 非空 → 输出 recurrenceReminderTime（如 "08:00"）
 * - 否则 reminder 非空 → 输出 reminder（如 "2026-12-31T09:00"）
 * - 两者都有时优先输出 recurrenceReminderTime（日常提醒更有用）
 *
 * @param task UniFFI 生成的任务数据记录
 * @return 包含任务关键信息的 JSON 对象，null 字段已省略
 */
fun taskToJson(task: TaskData): JSONObject = JSONObject().apply {
    put("id", task.id)
    put("name", task.name)
    // kind 始终返回：模型必须知道手里这条是任务还是笔记（能否勾选、该走哪个入口）
    put("kind", task.kind)
    put("status", task.status)
    put("priority", task.priority)
    // description: 非空时才输出
    if (task.description != null) put("description", task.description)
    // reminder 合并输出：循环提醒时间优先于一次性提醒
    if (task.recurrenceRule == "daily" && task.recurrenceReminderTime != null) {
        put("reminder", task.recurrenceReminderTime)
    } else if (task.reminder != null) {
        put("reminder", task.reminder)
    }
    // 关联 ID: 非空时才输出
    if (task.parentId != null) put("parent_id", task.parentId)
    if (task.categoryId != null) put("category_id", task.categoryId)
    if (task.groupId != null) put("group_id", task.groupId)
    // focus_seconds: 仅非零时输出，0 是默认值
    if (task.focusSeconds > 0) put("focus_seconds", task.focusSeconds)
    // expected_minutes: 仅在已规划时输出，缺省即「未规划时长」
    if (task.expectedMinutes != null) put("expected_minutes", task.expectedMinutes)
    // completed_at: 仅已完成时输出
    if (task.completedAt != null) put("completed_at", task.completedAt)
}

/**
 * 从统一的 reminder 参数解析出 Rust 层需要的三个字段。
 *
 * 解析规则：
 * - 包含 "T"（如 "2026-12-31T09:00"）→ 一次性提醒，设置 reminder
 * - 仅包含 ":"（如 "08:00"）→ 每日循环提醒，设置 recurrence_rule="daily" + recurrence_reminder_time
 * - 不匹配上述格式 → 按原始值作为一次性提醒处理
 *
 * @param reminder 统一的 reminder 参数值
 * @return Triple<recurrenceRule, recurrenceReminderTime, reminder> 对应 Rust 层的三个字段
 */
fun parseReminderParam(reminder: String): Triple<String?, String?, String?> {
    // 格式判断：包含 "T" 说明是日期时间格式（如 2026-12-31T09:00）→ 一次性提醒
    return if (reminder.contains("T")) {
        Triple(null, null, reminder)
    } else {
        // 不包含 "T"，视为每日循环提醒时间（如 "08:00"）
        Triple("daily", reminder, null)
    }
}

/**
 * 将任务列表序列化为 JSON 数组。
 *
 * @param tasks 任务数据列表
 * @return JSON 数组，每个元素是一个任务的完整信息
 */
fun taskListToJson(tasks: List<TaskData>): JSONArray = JSONArray().apply {
    for (task in tasks) {
        put(taskToJson(task))
    }
}

/**
 * 实体查询的统一返回结构。
 *
 * 为什么统一：历史上 task 通道返回顶层 `tasks`、note 通道返回顶层 `notes`，
 * 模型与前端各写一套解析分支，行为容易分叉。统一为 `{ "items": [...], "count": n }`，
 * 元素自带 `kind` 区分任务/笔记。
 *
 * @param entities 查询到的实体列表（任务或笔记，可能混合）
 * @param extra    需要附带的上下文键值（matched_checklists 等），并入返回对象
 */
fun entityQueryResult(entities: List<TaskData>, vararg extra: Pair<String, Any>): String {
    return JSONObject().apply {
        put("items", taskListToJson(entities))
        put("count", entities.size)
        extra.forEach { (k, v) -> put(k, v) }
    }.toString()
}

// ═══════════════════════════════════════════════════════════════════
// 统一变更集 —— 所有更新路径（id / ids / name / names）共用
// ═══════════════════════════════════════════════════════════════════

/**
 * 任务/笔记的组合变更集 —— 一次 `update_task_full` 调用要提交的全部内容。
 *
 * 存在意义（B1 结构性修复）：旧实现里 id/ids/name/names 四条定位路径各自拼字段、
 * 各自调 `updateTaskParent` / `updateTaskGroup` / `updateTask`，只要其中某条路径漏一段，
 * 就会出现「归属写进去了、字段更新报 No fields to update」这类假失败。现在四条路径
 * 只负责「把定位符解析成 id」，字段解析与执行完全共用本类 + [applyTaskChangeSet]。
 *
 * @param fields                字段变更（Rust 侧为 Option，null = 不改）
 * @param setGroup              是否应用分组变更（false = 不动分组）
 * @param groupId               目标分组，配合 setGroup=true 使用；null 表示清除分组
 * @param setParent             是否应用父子变更（false = 不动父子关系）
 * @param parentId              目标父级，配合 setParent=true 使用；null 表示提升为根
 * @param clearExpectedMinutes  是否清除预计时长
 * @param clearReminder         是否清除一次性提醒
 * @param descriptionAppend     追加模式的文本（仅笔记用）；null 表示非追加模式
 */
class TaskChangeSet(
    val fields: FieldUpdates,
    val setGroup: Boolean = false,
    val groupId: String? = null,
    val setParent: Boolean = false,
    val parentId: String? = null,
    val clearExpectedMinutes: Boolean = false,
    val clearReminder: Boolean = false,
    val descriptionAppend: String? = null,
) {
    /** 是否一个字段都没改（用于给出「没有任何变更」的清晰错误，而不是静默成功） */
    val isEmpty: Boolean
        get() = fields.isEmpty && !setGroup && !setParent && !clearExpectedMinutes &&
            !clearReminder && descriptionAppend == null
}

/**
 * 字段级变更 —— 对应 Rust `update_task_full` 的 Option 参数组。
 *
 * @param name              新名称
 * @param description       新正文（追加模式时由 TaskChangeSet 合成后填入）
 * @param priority          优先级
 * @param status            状态
 * @param kind              "task" / "note"，用于互转
 * @param expectedMinutes   预计时长（分钟）
 * @param categoryId        目标清单
 * @param reminder          一次性提醒
 * @param recurrenceRule    循环规则
 * @param recurrenceReminderTime 循环提醒时间
 */
data class FieldUpdates(
    val name: String? = null,
    val description: String? = null,
    val priority: String? = null,
    val status: String? = null,
    val kind: String? = null,
    val expectedMinutes: Long? = null,
    val categoryId: String? = null,
    val reminder: String? = null,
    val recurrenceRule: String? = null,
    val recurrenceReminderTime: String? = null,
) {
    val isEmpty: Boolean
        get() = name == null && description == null && priority == null && status == null &&
            kind == null && expectedMinutes == null && categoryId == null && reminder == null &&
            recurrenceRule == null && recurrenceReminderTime == null
}

/**
 * 把工具参数解析成 [TaskChangeSet]。
 *
 * 只做「参数 → 变更集」的翻译，不碰数据库；归属名称解析集中在这里，
 * 保证 id / ids / name / names 四条路径拿到的语义完全一致。
 *
 * @param params 工具参数
 * @param core   NionCore（用于解析 group/checklist 名称）
 * @param entityKindHint 实体类型提示："note" 时默认把 kind 写成 note；null 表示不改 kind
 * @return 成功返回变更集；解析失败返回 null 并附带错误 JSON
 */
fun parseTaskChangeSet(
    params: JSONObject,
    core: NionCore,
    entityKindHint: String? = null,
): Pair<TaskChangeSet?, String> {
    // reminder 参数：含 "T" → 一次性；否则视为每日循环时间
    val reminderRaw = params.optString("reminder", "").takeIf { it.isNotEmpty() }
    val (recRule, recTime, reminder) = if (reminderRaw != null) {
        parseReminderParam(reminderRaw)
    } else {
        Triple(null, null, null)
    }

    // 归属目标：group 优先于 checklist（group 隐含所在清单）
    val moveChecklist = params.optString("checklist", "").takeIf { it.isNotEmpty() }
    val moveGroup = params.optString("group", "").takeIf { it.isNotEmpty() }

    var categoryId: String? = null
    var groupId: String? = null
    var setGroup = false

    if (moveGroup != null) {
        val allChecklists = core.getChecklists()
        val allGroups = allChecklists.flatMap { core.getGroupsByChecklist(it.id) }
        val exact = allGroups.filter { it.name == moveGroup }
        val matched = if (exact.isNotEmpty()) exact else allGroups.filter { it.name.contains(moveGroup) }
        if (matched.isEmpty()) return null to """{"error":"未找到名称包含「$moveGroup」的分组"}"""
        if (matched.size > 1) {
            return null to """{"error":"找到多个名称包含「$moveGroup」的分组，请同时指定 checklist"}"""
        }
        groupId = matched[0].id
        setGroup = true
        categoryId = matched[0].checklistId
    } else if (moveChecklist != null) {
        val matchedChecklists = resolveChecklistsByName(moveChecklist, core)
        if (matchedChecklists.isEmpty()) {
            return null to """{"error":"未找到名称包含「$moveChecklist」的清单"}"""
        }
        if (matchedChecklists.size > 1) {
            return null to """{"error":"找到多个名称包含「$moveChecklist」的清单"}"""
        }
        categoryId = matchedChecklists[0].id
        // 移到新清单时清空旧分组：分组属于清单，跨清单保留旧分组会指到别人的分组
        setGroup = true
        groupId = null
    }

    // parent：有值则挂载，显式空串则提升为根
    val parentRaw = params.optString("parent", "")
    val hasParentParam = params.has("parent")
    var setParent = false
    var parentId: String? = null
    if (hasParentParam) {
        setParent = true
        if (parentRaw.isNotEmpty()) {
            val (resolved, err) = resolveTaskIdByName(parentRaw, null, null, core)
            if (resolved == null) return null to err
            parentId = resolved
        }
    }

    // 预计时长：0/负数视为非法，直接拒绝而不是静默忽略 —— 模型给了错值必须让它知道
    val minutesRaw = params.opt("expected_minutes")
    val expected = when (minutesRaw) {
        null, JSONObject.NULL -> null
        is Number -> minutesRaw.toLong()
        is String -> minutesRaw.trim().toLongOrNull() ?: return null to """{"error":"expected_minutes 必须是整数分钟"}"""
        else -> return null to """{"error":"expected_minutes 必须是整数分钟"}"""
    }
    if (expected != null && expected <= 0) {
        return null to """{"error":"expected_minutes 必须为正整数（分钟）；要清除请传 clear_expected_minutes=true"}"""
    }
    val clearMinutes = params.optBoolean("clear_expected_minutes", false)

    // 正文：append 模式（仅笔记）合成后的文本在这里先留空，执行时按当前内容拼接
    val descriptionParam = params.optString("description", "").takeIf { it.isNotEmpty() }
    val appendMode = params.optBoolean("append", false) && descriptionParam != null

    // kind：显式 kind 参数优先；否则按调用方给的实体类型提示
    val kindParam = params.optString("kind", "").takeIf { it.isNotEmpty() }
        ?: entityKindHint

    val fields = FieldUpdates(
        name = params.optString("new_name", "").takeIf { it.isNotEmpty() },
        description = if (appendMode) null else descriptionParam,
        priority = params.optString("priority", "").takeIf { it.isNotEmpty() },
        status = params.optString("status", "").takeIf { it.isNotEmpty() },
        kind = kindParam,
        expectedMinutes = expected,
        categoryId = categoryId,
        reminder = reminder,
        recurrenceRule = recRule,
        recurrenceReminderTime = recTime,
    )

    return TaskChangeSet(
        fields = fields,
        setGroup = setGroup,
        groupId = groupId,
        setParent = setParent,
        parentId = parentId,
        clearExpectedMinutes = clearMinutes,
        clearReminder = params.optBoolean("clear_reminder", false),
        descriptionAppend = if (appendMode) descriptionParam else null,
    ) to ""
}

/**
 * 对单个实体执行变更集 —— 一次 `update_task_full`，字段与归属同事务提交。
 *
 * @param id    目标实体 ID
 * @param change 变更集
 * @param core  NionCore
 * @return 成功时 Pair<更新后的实体, "">；失败时 Pair<null, 错误 JSON>
 */
fun applyTaskChangeSet(id: String, change: TaskChangeSet, core: NionCore): Pair<TaskData?, String> {
    // 追加模式：先读当前正文再拼接（读取与写入在同一调用栈内完成，
    // 期间不会有其它写者插入 —— Rust 侧单 Mutex + 事务保证）
    val effectiveDescription = if (change.descriptionAppend != null) {
        val existing = core.getTask(id).description ?: ""
        if (existing.isEmpty()) change.descriptionAppend else "$existing\n\n${change.descriptionAppend}"
    } else {
        change.fields.description
    }

    return try {
        val updated = core.updateTaskFull(
            id = id,
            name = change.fields.name,
            description = effectiveDescription,
            priority = change.fields.priority,
            status = change.fields.status,
            kind = change.fields.kind,
            expectedMinutes = change.fields.expectedMinutes,
            clearExpectedMinutes = change.clearExpectedMinutes,
            categoryId = change.fields.categoryId,
            setGroup = change.setGroup,
            groupId = change.groupId,
            setParent = change.setParent,
            parentId = change.parentId,
            reminder = change.fields.reminder,
            clearReminder = change.clearReminder,
            recurrenceRule = change.fields.recurrenceRule,
            recurrenceReminderTime = change.fields.recurrenceReminderTime,
        )
        updated to ""
    } catch (e: Exception) {
        null to """{"error":"${(e.message ?: "更新失败").replace("\"", "\\\"")}"}"""
    }
}

/**
 * 将 [ChecklistData] 序列化为 JSON 对象。
 *
 * @param checklist UniFFI 生成的清单数据记录
 * @return 包含完整清单信息的 JSON 对象
 */
fun checklistToJson(checklist: ChecklistData): JSONObject = JSONObject().apply {
    put("id", checklist.id)
    put("name", checklist.name)
    put("created_at", checklist.createdAt)
    // 清单类型：task=任务型，notebook=笔记型，回传给 LLM 以便其感知清单类型
    put("checklist_type", checklist.checklistType)
}

/**
 * 将清单列表序列化为 JSON 数组。
 *
 * @param checklists 清单数据列表
 * @return JSON 数组
 */
fun checklistListToJson(checklists: List<ChecklistData>): JSONArray = JSONArray().apply {
    for (cl in checklists) {
        put(checklistToJson(cl))
    }
}

/**
 * 将 [GroupData] 序列化为 JSON 对象。
 *
 * @param group UniFFI 生成的分组数据记录
 * @return 包含完整分组信息的 JSON 对象
 */
fun groupToJson(group: GroupData): JSONObject = JSONObject().apply {
    put("id", group.id)
    put("name", group.name)
    put("checklist_id", group.checklistId)
    if (group.color != null) put("color", group.color)
}

/**
 * 将分组列表序列化为 JSON 数组。
 *
 * @param groups 分组数据列表
 * @return JSON 数组
 */
fun groupListToJson(groups: List<GroupData>): JSONArray = JSONArray().apply {
    for (g in groups) {
        put(groupToJson(g))
    }
}

// ═══════════════════════════════════════════════════════════════════
// 名称解析辅助函数
// 供 QueryTool / CreateTool 等工具共用的名称→ID 解析逻辑
// 匹配策略：精确匹配优先，包含匹配兜底，多匹配合并返回
// ═══════════════════════════════════════════════════════════════════

/**
 * 按名称模糊匹配清单。
 * 精确匹配优先，无精确匹配时用包含匹配，多匹配合并返回。
 */
private fun resolveChecklistsByName(name: String, core: NionCore): List<ChecklistData> {
    val all = core.getChecklists()
    val exact = all.filter { it.name == name }
    if (exact.isNotEmpty()) return exact
    return all.filter { it.name.contains(name) }
}

/**
 * 按名称模糊匹配分组，在指定清单范围内。
 * 精确匹配优先，无精确匹配时用包含匹配，多匹配合并返回。
 */
private fun resolveGroupsByName(name: String, checklistIds: List<String>, core: NionCore): List<GroupData> {
    val allGroups = checklistIds.flatMap { core.getGroupsByChecklist(it) }
    val exact = allGroups.filter { it.name == name }
    if (exact.isNotEmpty()) return exact
    return allGroups.filter { it.name.contains(name) }
}

/**
 * 按名称 + 可选范围解析唯一任务 ID。
 * 精确匹配优先，包含匹配兜底，多匹配时要求加 checklist/group 缩小范围。
 *
 * @param name          任务名称（精确或模糊匹配）
 * @param checklistName 可选，按清单名称缩小搜索范围
 * @param groupName     可选，按分组名称缩小搜索范围
 * @param core          NionCore 单例
 * @return 成功时 Pair<ID, "">，失败时 Pair<null, 错误JSON>
 */
private fun resolveTaskIdByName(
    name: String,
    checklistName: String?,
    groupName: String?,
    core: NionCore
): Pair<String?, String> {
    var tasks = core.getTasks()

    // 按 checklist 名称缩小范围
    if (checklistName != null) {
        val matchedChecklists = resolveChecklistsByName(checklistName, core)
        if (matchedChecklists.isEmpty()) {
            return null to """{"error":"未找到名称包含「$checklistName」的清单"}"""
        }
        val checklistIds = matchedChecklists.map { it.id }.toSet()
        tasks = tasks.filter { it.categoryId != null && it.categoryId in checklistIds }
    }

    // 按 group 名称缩小范围
    if (groupName != null) {
        val checklistIds = if (checklistName != null) {
            resolveChecklistsByName(checklistName, core).map { it.id }
        } else {
            core.getChecklists().map { it.id }
        }
        val matchedGroups = resolveGroupsByName(groupName, checklistIds, core)
        if (matchedGroups.isEmpty()) {
            return null to """{"error":"未找到名称包含「$groupName」的分组"}"""
        }
        val groupIds = matchedGroups.map { it.id }.toSet()
        tasks = tasks.filter { it.groupId != null && it.groupId in groupIds }
    }

    if (tasks.isEmpty()) {
        return null to """{"error":"在指定范围内未找到名称包含「$name」的任务"}"""
    }

    // 精确匹配优先
    val exact = tasks.filter { it.name == name }
    if (exact.size == 1) return exact[0].id to ""
    if (exact.size > 1) {
        val hint = buildScopeHint(checklistName, groupName)
        return null to """{"error":"找到多个名称为「$name」的任务$hint"}"""
    }

    // 包含匹配兜底
    val fuzzy = tasks.filter { it.name.contains(name) }
    if (fuzzy.isEmpty()) return null to """{"error":"未找到名称包含「$name」的任务"}"""
    if (fuzzy.size == 1) return fuzzy[0].id to ""
    val hint = buildScopeHint(checklistName, groupName)
    return null to """{"error":"找到多个名称包含「$name」的任务$hint"}"""
}

/**
 * 按名称 + 可选清单范围解析唯一分组 ID。
 * 精确匹配优先，包含匹配兜底，多匹配时要求加 checklist 缩小范围。
 *
 * @param name          分组名称
 * @param checklistName 可选，按清单名称缩小搜索范围
 * @param core          NionCore 单例
 * @return 成功时 Pair<ID, "">，失败时 Pair<null, 错误JSON>
 */
private fun resolveGroupIdByName(
    name: String,
    checklistName: String?,
    core: NionCore
): Pair<String?, String> {
    val checklistIds = if (checklistName != null) {
        val matched = resolveChecklistsByName(checklistName, core)
        if (matched.isEmpty()) {
            return null to """{"error":"未找到名称包含「$checklistName」的清单"}"""
        }
        matched.map { it.id }
    } else {
        core.getChecklists().map { it.id }
    }

    val allGroups = checklistIds.flatMap { core.getGroupsByChecklist(it) }
    if (allGroups.isEmpty()) {
        return null to """{"error":"在指定范围内未找到分组"}"""
    }

    // 精确匹配
    val exact = allGroups.filter { it.name == name }
    if (exact.size == 1) return exact[0].id to ""
    if (exact.size > 1) {
        val hint = if (checklistName == null) "，请加 checklist 参数缩小范围" else ""
        return null to """{"error":"找到多个名称为「$name」的分组$hint"}"""
    }

    // 包含匹配
    val fuzzy = allGroups.filter { it.name.contains(name) }
    if (fuzzy.isEmpty()) return null to """{"error":"未找到名称包含「$name」的分组"}"""
    if (fuzzy.size == 1) return fuzzy[0].id to ""
    val hint = if (checklistName == null) "，请加 checklist 参数缩小范围" else ""
    return null to """{"error":"找到多个名称包含「$name」的分组$hint"}"""
}

/**
 * 构建多匹配时的范围提示语。
 * 根据 checklist/group 参数是否已指定，给出不同的缩小范围建议。
 */
private fun buildScopeHint(checklistName: String?, groupName: String?): String {
    return when {
        checklistName == null && groupName == null -> "，请加 checklist 或 group 参数缩小范围"
        groupName == null -> "，请加 group 参数进一步缩小范围"
        else -> ""
    }
}

// ═══════════════════════════════════════════════════════════════════
// 统一工具实现
// ═══════════════════════════════════════════════════════════════════

/**
 * 统一查询工具 —— 名称驱动，LLM 无需知道 ID。
 *
 * 通过 entity_type 路由到不同的查询逻辑：
 * - task: 按 name 搜任务名 / 按 checklist+group 名称筛选 / 无参数返回全部
 * - checklist: 按 name 筛选 / 无参数返回全部
 * - group: 按 checklist 名称查某清单下的分组
 * - weather: 天气查询
 *
 * 所有名称参数均支持模糊匹配，精确匹配优先，包含匹配兜底，
 * 多匹配合并返回并在结果中标注 matched_checklists / matched_groups。
 *
 * Agent 场景示例：
 * - "我有哪些任务？" → entity_type="task"
 * - "学习清单下有什么？" → entity_type="task", checklist="学习"
 * - "学习数学" → entity_type="task", name="学习数学"
 * - "有哪些清单？" → entity_type="checklist"
 * - "学习清单的分组" → entity_type="group", checklist="学习"
 */
object QueryTool : Tool {
    override val name = "query"
    override val affectsData = emptySet<DataType>()
    override val description =
        "查询数据。entity_type: task/checklist/group/note/weather。" +
            "可用 checklist/group 按名称筛选，name 按名称搜索。" +
            "note 通道带 name 时全文搜索（含子笔记），不带 name 时枚举笔记。" +
            "task 通道可加 kind 只查任务或只查笔记。结果统一为 items 数组，每项带 kind。"

    private val schema = """
    {
        "type": "object",
        "properties": {
            "entity_type": {
                "type": "string",
                "enum": ["task", "checklist", "group", "note", "weather"]
            },
            "checklist": { "type": "string" },
            "group": { "type": "string" },
            "name": { "type": "string" },
            "kind": {
                "type": "string",
                "enum": ["task", "note"],
                "description": "按实体类型过滤：task=可勾选任务，note=笔记。不填则两者都返回"
            },
            "type": {
                "type": "string",
                "enum": ["current", "forecast"]
            }
        },
        "required": ["entity_type"]
    }
    """.trimIndent()

    override fun parametersSchema(): JSONObject = JSONObject(schema)

    override suspend fun execute(params: JSONObject, core: NionCore): String {
        val entityType = params.getString("entity_type")

        return when (entityType) {
            "task" -> executeTaskQuery(params, core)
            "checklist" -> executeChecklistQuery(params, core)
            "group" -> executeGroupQuery(params, core)
            "note" -> executeNoteQuery(params, core)
            "weather" -> executeWeatherQuery(params, core)
            else -> """{"error":"不支持的 entity_type: $entityType"}"""
        }
    }

    /**
     * 任务查询路由。
     * 优先级：name 按名字搜 > checklist+group > checklist > group > 全部。
     *
     * kind 参数在最后统一过滤一次（而不是每条分支各过滤一遍），
     * 保证任何筛选路径下 kind 语义一致。
     */
    private fun executeTaskQuery(params: JSONObject, core: NionCore): String {
        val raw = executeTaskQueryUnfiltered(params, core)
        val kindFilter = params.optString("kind", "").takeIf { it.isNotEmpty() } ?: return raw
        return filterQueryResultByKind(raw, kindFilter)
    }

    /**
     * 对统一查询结果按 kind 过滤并重算 count。
     *
     * @param raw        `{ "items": [...], "count": n, ... }` 结构的 JSON 字符串
     * @param kind       目标 kind（"task" / "note"）
     */
    private fun filterQueryResultByKind(raw: String, kind: String): String {
        val obj = try {
            JSONObject(raw)
        } catch (_: Exception) {
            return raw
        }
        val items = obj.optJSONArray("items") ?: return raw
        val kept = JSONArray()
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            if (item.optString("kind") == kind) kept.put(item)
        }
        obj.put("items", kept)
        obj.put("count", kept.length())
        return obj.toString()
    }

    private fun executeTaskQueryUnfiltered(params: JSONObject, core: NionCore): String {
        val nameParam = params.optString("name", "").takeIf { it.isNotEmpty() }
        val checklistParam = params.optString("checklist", "").takeIf { it.isNotEmpty() }
        val groupParam = params.optString("group", "").takeIf { it.isNotEmpty() }

        // 按 name 搜索任务：从全部任务中按名称模糊匹配
        if (nameParam != null && checklistParam == null && groupParam == null) {
            val allTasks = core.getTasks()
            // 精确匹配优先
            val exact = allTasks.filter { it.name == nameParam }
            val matched = if (exact.isNotEmpty()) exact else allTasks.filter { it.name.contains(nameParam) }
            return entityQueryResult(matched)
        }

        // 按清单名称 + 分组名称筛选
        if (checklistParam != null) {
            val matchedChecklists = resolveChecklistsByName(checklistParam, core)
            if (matchedChecklists.isEmpty()) {
                return """{"error":"未找到名称包含「$checklistParam」的清单"}"""
            }
            val checklistIds = matchedChecklists.map { it.id }

            // 有 group 参数时进一步按分组名筛选
            if (groupParam != null) {
                val matchedGroups = resolveGroupsByName(groupParam, checklistIds, core)
                if (matchedGroups.isEmpty()) {
                    return """{"error":"在匹配的清单中未找到名称包含「$groupParam」的分组"}"""
                }
                // 每个匹配的分组查询其下任务，合并结果
                val tasks = matchedGroups.flatMap { group ->
                    core.getTasksByCategory(group.checklistId, group.id)
                }
                // 多匹配时标注匹配了哪些清单和分组（额外键并入统一返回结构）
                val extras = mutableListOf<Pair<String, Any>>()
                if (matchedChecklists.size > 1) {
                    extras += "matched_checklists" to JSONArray(matchedChecklists.map { it.name })
                }
                if (matchedGroups.size > 1) {
                    extras += "matched_groups" to JSONArray(matchedGroups.map { it.name })
                }
                return entityQueryResult(tasks, *extras.toTypedArray())
            }

            // 只按清单筛选，不限定分组
            val tasks = checklistIds.flatMap { cid ->
                core.getTasksByCategory(cid, null)
            }
            return if (matchedChecklists.size > 1) {
                entityQueryResult(tasks, "matched_checklists" to JSONArray(matchedChecklists.map { it.name }))
            } else {
                entityQueryResult(tasks)
            }
        }

        // 只按分组名称筛选，无 checklist：全局搜分组名匹配 → 查询任务
        if (groupParam != null) {
            val allChecklists = core.getChecklists()
            val matchedGroups = resolveGroupsByName(groupParam, allChecklists.map { it.id }, core)
            if (matchedGroups.isEmpty()) {
                return """{"error":"未找到名称包含「$groupParam」的分组"}"""
            }
            val tasks = matchedGroups.flatMap { group ->
                core.getTasksByCategory(group.checklistId, group.id)
            }
            return if (matchedGroups.size > 1) {
                entityQueryResult(tasks, "matched_groups" to JSONArray(matchedGroups.map { it.name }))
            } else {
                entityQueryResult(tasks)
            }
        }

        // 无筛选参数：返回全部任务
        return entityQueryResult(core.getTasks())
    }

    /**
     * 清单查询。
     * 有 name 参数时按名称模糊筛选，无参数时返回全部。
     */
    private fun executeChecklistQuery(params: JSONObject, core: NionCore): String {
        val nameParam = params.optString("name", "").takeIf { it.isNotEmpty() }
        val allChecklists = core.getChecklists()

        val matched = if (nameParam != null) {
            // 精确匹配优先
            val exact = allChecklists.filter { it.name == nameParam }
            if (exact.isNotEmpty()) exact else allChecklists.filter { it.name.contains(nameParam) }
        } else {
            allChecklists
        }

        return JSONObject().apply {
            put("checklists", checklistListToJson(matched))
            put("count", matched.size)
        }.toString()
    }

    /**
     * 分组查询 —— 按 checklist 名称找到清单，再返回其下所有分组。
     * 多个清单匹配时合并所有分组结果。
     */
    private fun executeGroupQuery(params: JSONObject, core: NionCore): String {
        val checklistParam = params.optString("checklist", "").takeIf { it.isNotEmpty() }
            ?: return """{"error":"查询分组时必须指定 checklist 参数"}"""

        val matchedChecklists = resolveChecklistsByName(checklistParam, core)
        if (matchedChecklists.isEmpty()) {
            return """{"error":"未找到名称包含「$checklistParam」的清单"}"""
        }

        // 合并所有匹配清单下的分组
        val groups = matchedChecklists.flatMap { core.getGroupsByChecklist(it.id) }
        return JSONObject().apply {
            put("groups", groupListToJson(groups))
            put("count", groups.size)
            if (matchedChecklists.size > 1) {
                put("matched_checklists", JSONArray(matchedChecklists.map { it.name }))
            }
        }.toString()
    }

    /**
     * 天气查询 —— 查看用户所在位置的当前天气或未来预报。
     *
     * 从 NionApp 获取 Context，调用 WeatherService 获取数据，
     * 根据 type 参数返回格式化的天气文本给 LLM。
     *
     * @param params 包含 type "current" 或 "forecast" 的参数
     * @param core   NionCore 单例，用于读取位置缓存和天气缓存
     * @return 格式化的天气信息文本
     */
    private suspend fun executeWeatherQuery(params: JSONObject, core: NionCore): String {
        val type = params.optString("type", "current")

        val app = NionApp.instance
            ?: return """{"error":"无法获取应用上下文"}"""

        val weather = WeatherService.fetchWeather(app, core)
            ?: return """{"error":"获取天气数据失败，可能是定位不可用或网络问题。请告诉用户天气功能暂时不可用，建议检查定位权限和网络连接。"}"""

        return when (type) {
            "current" -> formatCurrentWeather(weather)
            "forecast" -> formatForecast(weather)
            else -> """{"error":"不支持的天气查询类型: $type，可选：current, forecast"}"""
        }
    }

    /**
     * 格式化当前天气实况为 LLM 可理解的文本。
     *
     * 输出格式包含：
     * - 天气描述，含白天/夜间标识
     * - 实际温度 + 体感温度
     * - 湿度、风速、降水量
     * - 云量、能见度
     * - 空气质量，PM2.5、AQI
     * - 今日温度范围
     */
    private fun formatCurrentWeather(data: FullWeatherData): String {
        val current = data.current
        val desc = weatherDescription(current.weatherCode)
        val dayNight = if (current.isDay) "" else "，夜间"

        val todayRange = if (data.daily.days.isNotEmpty()) {
            val today = data.daily.days[0]
            "\n今日温度范围：${"%.0f".format(today.tempMin)}°C ~ ${"%.0f".format(today.tempMax)}°C"
        } else {
            ""
        }

        return buildString {
            append("当前天气：$desc$dayNight\n")
            append("温度：${"%.1f".format(current.temperature)}°C")
            // 体感温度与实际温度差异超过 1°C 时显示
            if (kotlin.math.abs(current.apparentTemperature - current.temperature) > 1.0) {
                append("，体感 ${"%.1f".format(current.apparentTemperature)}°C")
            }
            append("\n")
            append("湿度：${current.humidity}%\n")
            append("风速：${"%.1f".format(current.windSpeed)} km/h\n")
            append("降水量：${"%.1f".format(current.precipitation)} mm\n")
            append("云量：${current.cloudCover}%\n")
            // 能见度低于 5km 时特别标注，大雾/霾预警
            val visKm = current.visibility / 1000.0
            if (visKm < 5.0) {
                append("能见度：${"%.1f".format(visKm)} km ⚠ 能见度较低\n")
            } else {
                append("能见度：${"%.1f".format(visKm)} km\n")
            }

            // 空气质量信息
            data.airQuality?.let { aq ->
                append("空气质量：AQI ${aq.aqi}，${aqiDescription(aq.aqi)}，PM2.5 ${"%.0f".format(aq.pm25)} μg/m³\n")
            }

            append(todayRange)
        }
    }

    /**
     * 格式化天气预报为 LLM 可理解的文本。
     *
     * 包含两部分：
     * 1. 未来 24 小时逐小时预报（温度、降水概率、天气、风速、UV）
     * 2. 未来 7 天逐日预报（最高/最低温、总降水、最大风速、UV峰值）
     */
    private fun formatForecast(data: FullWeatherData): String {
        val sb = StringBuilder()

        sb.appendLine("=== 未来 24 小时逐小时预报 ===")
        for (h in data.hourly.hours) {
            val timeLabel = h.hour.substring(11)
            val desc = weatherDescription(h.weatherCode)
            val precipMark = if (h.precipitationProb >= 50) " ⚠" else ""
            sb.appendLine(
                "$timeLabel | $desc | ${"%.0f".format(h.temperature)}°C | " +
                    "降水${h.precipitationProb}% | " +
                    "风速${"%.0f".format(h.windSpeed)}km/h | " +
                    "UV${"%.0f".format(h.uvIndex)}$precipMark"
            )
        }

        sb.appendLine()

        sb.appendLine("=== 未来 7 天逐日预报 ===")
        for (d in data.daily.days) {
            val dateLabel = d.date.substring(5)
            sb.appendLine(
                "$dateLabel | ${"%.0f".format(d.tempMin)}°C ~ " +
                    "${"%.0f".format(d.tempMax)}°C | " +
                    "降水${d.precipitationProbabilityMax}% " +
                    "${"%.1f".format(d.precipitationSum)}mm | " +
                    "最大风速${"%.0f".format(d.windSpeedMax)}km/h | " +
                    "UV峰值${"%.0f".format(d.uvIndexMax)}"
            )
        }

        return sb.toString()
    }

    /**
     * 笔记查询 —— 搜索或枚举笔记（kind=note 的实体）。
     *
     * 两种用法：
     * - 带 name：全文搜索标题与正文，包含子笔记（AI 需要完整视图）；
     * - 不带 name：按 checklist（可选）枚举笔记，用于「我有哪些笔记」这类问题。
     *
     * 返回结构与 task 通道统一为 `{ "items": [...], "count": n }`，元素含 kind。
     */
    private fun executeNoteQuery(params: JSONObject, core: NionCore): String {
        val nameParam = params.optString("name", "").takeIf { it.isNotEmpty() }
        val checklistParam = params.optString("checklist", "").takeIf { it.isNotEmpty() }

        // 解析可选的清单范围（笔记可以出现在任意清单里，按名称匹配即可，不再要求 notebook 型）
        val checklistId: String? = if (checklistParam != null) {
            val matched = resolveChecklistsByName(checklistParam, core)
            if (matched.isEmpty()) {
                return """{"error":"未找到名称包含「$checklistParam」的清单"}"""
            }
            matched.first().id
        } else null

        // 有 name → 搜索（含子笔记）；无 name → 枚举
        val notes = core.searchNotes(nameParam, checklistId, nameParam != null)
        return entityQueryResult(notes)
    }
}

/**
 * 读取可选的整数字段。
 *
 * 与 `optInt` 的区别：JSON 里可能传来字符串形式的数字（模型常把 "45" 当字符串），
 * 也可能传来 null；这里统一返回 null 表示「未提供/非法」，由调用方决定语义。
 */
fun JSONObject.optLongOrNull(key: String): Long? {
    if (!has(key) || isNull(key)) return null
    val v = opt(key)
    return when (v) {
        is Number -> v.toLong()
        is String -> v.trim().toLongOrNull()
        else -> null
    }
}

/**
 * 统一创建工具 —— 名称驱动，所有归属关系用名称指定。
 *
 * 支持批量创建：传 items 数组可一次创建多个实体。
 * 外层 checklist/group/parent 作为批量默认值，item 未指定时自动继承。
 *
 * 归属解析规则：
 * - checklist: 按名称匹配清单，未找到则报错
 * - group: 先在匹配到的 checklist 下找分组，未找到则自动创建该分组
 * - parent: 按名称匹配父任务，必须唯一匹配，多匹配时报错
 *
 * Agent 场景示例：
 * - "帮我建一个任务" → entity_type="task", name="买菜"
 * - "在学习清单下建任务" → entity_type="task", name="考研计划", checklist="学习"
 * - "在学习清单语文分组下建任务" → entity_type="task", name="背诵", checklist="学习", group="语文"
 * - "在学习数学下面建子任务" → entity_type="task", name="第一章", parent="学习数学"
 * - "建三个任务" → entity_type="task", items=[{"name":"买菜"},{"name":"跑步"},{"name":"读书"}]
 * - "建一个学习清单" → entity_type="checklist", name="学习"
 * - "在学习清单下建一个分组" → entity_type="group", name="数学", checklist="学习"
 */
object CreateTool : Tool {
    override val name = "create"
    override val affectsData = setOf(DataType.TASK_DATA)
    override val description =
        "创建实体，支持批量。checklist/group/parent 用名称指定归属。" +
            "当任务与时间相关时，必须根据任务类型填写 reminder 参数：" +
            "每日重复任务用 HH:MM 格式如 08:00，" +
            "一次性任务用 YYYY-MM-DDTHH:MM 格式如 2026-06-15T09:00。" +
            "不要只写标题不设时间。" +
            "note 通道创建笔记，同样支持 checklist/group/parent；" +
            "不懂预计工作量时不要填 expected_minutes。" +
            "创建清单时可传 checklist_type=\"notebook\" 来新建笔记型清单（默认 task）。" +
            "笔记与任务是同一种实体，可以互转，区别只是能否勾选完成。"

    /**
     * 参数 Schema。
     * reminder 字段已合并：传 "HH:MM" 表示每日循环提醒，传 "YYYY-MM-DDTHH:MM" 表示一次性提醒。
     */
    private val schema = """
    {
        "type": "object",
        "properties": {
            "entity_type": {
                "type": "string",
                "enum": ["task", "checklist", "group", "note"]
            },
            "name": { "type": "string" },
            "checklist_type": {
                "type": "string",
                "enum": ["task", "notebook"],
                "description": "清单类型，仅 entity_type=checklist 时有效。task=任务型清单(默认)，notebook=笔记型清单"
            },
            "description": { "type": "string" },
            "priority": {
                "type": "string",
                "enum": ["low", "medium", "high"]
            },
            "checklist": { "type": "string" },
            "group": { "type": "string" },
            "parent": { "type": "string" },
            "reminder": {
                "type": "string",
                "description": "提醒时间。\"HH:MM\"格式为每日循环提醒，\"YYYY-MM-DDTHH:MM\"格式为一次性提醒"
            },
            "expected_minutes": {
                "type": "integer",
                "description": "预计总工作量（分钟），正整数。用户没说要花多久就不要填"
            },
            "color": { "type": "string" },
            "items": {
                "type": "array",
                "items": { "type": "object" }
            }
        },
        "required": ["entity_type"]
    }
    """.trimIndent()

    override fun parametersSchema(): JSONObject = JSONObject(schema)

    override suspend fun execute(params: JSONObject, core: NionCore): String {
        val entityType = params.getString("entity_type")
        val itemsArray = params.optJSONArray("items")

        // 批量创建模式
        if (itemsArray != null && itemsArray.length() > 0) {
            return executeBatchCreate(entityType, itemsArray, params, core)
        }

        return when (entityType) {
            "task" -> executeCreateTask(params, core)
            "checklist" -> executeCreateChecklist(params, core)
            "group" -> executeCreateGroup(params, core)
            "note" -> executeCreateNote(params, core)
            else -> """{"error":"不支持的 entity_type: $entityType"}"""
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 名称解析辅助函数
    // ═══════════════════════════════════════════════════════════════

    /**
     * 按名称匹配清单，返回 ID。
     * 未找到时返回 null，调用方负责报错。
     */
    private fun resolveChecklistId(name: String, core: NionCore): String? {
        val matched = resolveChecklistsByName(name, core)
        return matched.firstOrNull()?.id
    }

    /**
     * 按名称匹配父任务，要求唯一匹配。
     * 精确匹配优先，包含匹配兜底。
     * 多匹配时返回 null，调用方负责报错。
     */
    private fun resolveParentId(name: String, core: NionCore): String? {
        val allTasks = core.getTasks()
        // 精确匹配
        val exact = allTasks.filter { it.name == name }
        if (exact.size == 1) return exact[0].id
        if (exact.size > 1) return null
        // 包含匹配
        val fuzzy = allTasks.filter { it.name.contains(name) }
        return if (fuzzy.size == 1) fuzzy[0].id else null
    }

    /**
     * 在指定清单下按名称查找分组，未找到则自动创建。
     *
     * @param groupName   分组名称
     * @param checklistId 清单 ID
     * @param core        NionCore 单例
     * @return 分组 ID
     */
    private fun resolveOrCreateGroupId(groupName: String, checklistId: String, core: NionCore): String {
        val groups = core.getGroupsByChecklist(checklistId)
        // 精确匹配
        val exact = groups.find { it.name == groupName }
        if (exact != null) return exact.id
        // 包含匹配
        val fuzzy = groups.find { it.name.contains(groupName) }
        if (fuzzy != null) return fuzzy.id
        // 未找到，自动创建分组
        val newGroup = core.createGroup(groupName, checklistId, null)
        return newGroup.id
    }

    /**
     * 解析任务创建所需的归属 ID。
     * 从 checklist/group/parent 名称参数解析出对应的 ID。
     *
     * @return 成功时返回 Triple<categoryId, groupId, parentId>，失败时返回错误 JSON 字符串
     */
    private fun resolveTaskOwnership(
        checklistName: String?, groupName: String?, parentName: String?, core: NionCore
    ): kotlin.Pair<Triple<String?, String?, String?>?, String> {
        // 解析 checklist
        var categoryId: String? = null
        if (checklistName != null) {
            categoryId = resolveChecklistId(checklistName, core)
                ?: return null to """{"error":"未找到名称包含「$checklistName」的清单"}"""
        }

        // 解析 group
        var groupId: String? = null
        if (groupName != null) {
            if (categoryId == null) {
                // 没指定 checklist，尝试全局找分组
                val allChecklists = core.getChecklists()
                val allGroups = allChecklists.flatMap { core.getGroupsByChecklist(it.id) }
                val exact = allGroups.filter { it.name == groupName }
                val matched = if (exact.isNotEmpty()) exact else allGroups.filter { it.name.contains(groupName) }
                if (matched.isEmpty()) {
                    return null to """{"error":"未找到名称包含「$groupName」的分组，请先指定 checklist 或手动创建分组"}"""
                }
                if (matched.size > 1) {
                    return null to """{"error":"找到多个名称包含「$groupName」的分组，请同时指定 checklist 缩小范围"}"""
                }
                groupId = matched[0].id
                // 自动继承分组所属的清单
                categoryId = matched[0].checklistId
            } else {
                // 在指定 checklist 下找分组，未找到则自动创建
                groupId = resolveOrCreateGroupId(groupName, categoryId, core)
            }
        }

        // 解析 parent
        var parentId: String? = null
        if (parentName != null) {
            parentId = resolveParentId(parentName, core)
                ?: return null to """{"error":"未找到唯一匹配「$parentName」的任务，请用更精确的名称指定父任务"}"""
        }

        return Triple(categoryId, groupId, parentId) to ""
    }

    // ═══════════════════════════════════════════════════════════════
    // 单个创建
    // ═══════════════════════════════════════════════════════════════

    /**
     * 创建任务。
     * checklist/group/parent 参数按名称解析为 ID。
     * reminder 参数按格式自动判断：含"T"为一次性提醒，否则为每日循环提醒。
     */
    private fun executeCreateTask(params: JSONObject, core: NionCore): String {
        val name = params.optString("name", "").takeIf { it.isNotEmpty() }
            ?: return """{"error":"创建任务时必须指定 name"}"""

        val checklistName = params.optString("checklist", "").takeIf { it.isNotEmpty() }
        val groupName = params.optString("group", "").takeIf { it.isNotEmpty() }
        val parentName = params.optString("parent", "").takeIf { it.isNotEmpty() }

        val (ownership, errorMsg) = resolveTaskOwnership(checklistName, groupName, parentName, core)
        if (ownership == null) return errorMsg

        val (categoryId, groupId, parentId) = ownership

        // 解析统一的 reminder 参数，拆分为 Rust 层的三个字段
        val reminderRaw = params.optString("reminder", "").takeIf { it.isNotEmpty() }
        val (recurrenceRule, recurrenceReminderTime, reminder) = if (reminderRaw != null) {
            parseReminderParam(reminderRaw)
        } else {
            Triple(null, null, null)
        }

        // expected_minutes 必须为正整数；给了非法值直接报错而不是静默丢弃，
        // 否则排期工具拿到「未规划时长」会把任务塞进 unplaced，用户却以为自己填了值
        val minutes = params.optLongOrNull("expected_minutes")
        if (minutes != null && minutes <= 0) {
            return """{"error":"expected_minutes 必须为正整数（分钟）"}"""
        }

        val task = core.createTaskEx(
            name = name,
            description = params.optString("description", "").takeIf { it.isNotEmpty() },
            priority = params.optString("priority", "medium"),
            categoryId = categoryId,
            parentId = parentId,
            groupId = groupId,
            recurrenceRule = recurrenceRule,
            recurrenceReminderTime = recurrenceReminderTime,
            reminder = reminder,
            // entity_type=task 时固定写 kind=task；用户想建笔记应走 entity_type=note
            kind = "task",
            expectedMinutes = minutes,
        )
        return JSONObject().apply {
            put("success", true)
            put("task", taskToJson(task))
        }.toString()
    }

    /**
     * 创建笔记 —— 在指定清单中新建一条 kind=note 的实体。
     * 笔记复用 task 结构：name=标题，description=Markdown 正文。
     *
     * 与 task 通道的差异（4.2 定稿）：
     * - checklist 可选：不指定时创建到收集箱（category_id 为空）；
     * - 支持 parent：可以在任务下挂子笔记，也可以在笔记下继续挂笔记或任务；
     * - 显式写入 kind='note'，不再依赖「目标清单是 notebook 型」推导类型。
     */
    private fun executeCreateNote(params: JSONObject, core: NionCore): String {
        val title = params.optString("name", "").takeIf { it.isNotEmpty() }
            ?: return """{"error":"创建笔记时必须指定 name（标题）"}"""

        val content = params.optString("description", "").takeIf { it.isNotEmpty() }
        val checklistName = params.optString("checklist", "").takeIf { it.isNotEmpty() }
        val groupName = params.optString("group", "").takeIf { it.isNotEmpty() }
        val parentName = params.optString("parent", "").takeIf { it.isNotEmpty() }

        // 复用任务归属解析：checklist/group/parent 三个名称口径与 task 通道完全一致
        val (ownership, errorMsg) = resolveTaskOwnership(checklistName, groupName, parentName, core)
        if (ownership == null) return errorMsg
        val (categoryId, groupId, parentId) = ownership

        val note = core.createTaskEx(
            name = title,
            description = content,
            priority = "medium",
            categoryId = categoryId,
            parentId = parentId,
            groupId = groupId,
            recurrenceRule = null,
            recurrenceReminderTime = null,
            reminder = null,
            kind = "note",
            expectedMinutes = params.optLongOrNull("expected_minutes"),
        )
        return JSONObject().apply {
            put("success", true)
            put("note", taskToJson(note))
            // 同时给 task 键：调用方按 entity_type=note 时取 note，其它情况也能读到
            put("task", taskToJson(note))
        }.toString()
    }

    /**
     * 创建清单。
     * checklist_type="notebook" 时创建笔记型清单，否则默认任务型。
     */
    private fun executeCreateChecklist(params: JSONObject, core: NionCore): String {
        val name = params.optString("name", "").takeIf { it.isNotEmpty() }
            ?: return """{"error":"创建清单时必须指定 name"}"""
        // 读取可选的清单类型，未指定时默认 task（任务型清单）
        val checklistType = params.optString("checklist_type", "task")
        val checklist = if (checklistType == "notebook") {
            core.createChecklistWithType(name, "notebook")
        } else {
            core.createChecklist(name)
        }
        return JSONObject().apply {
            put("success", true)
            put("checklist", checklistToJson(checklist))
        }.toString()
    }

    /**
     * 创建分组。
     * checklist 参数按名称解析为清单 ID。
     */
    private fun executeCreateGroup(params: JSONObject, core: NionCore): String {
        val name = params.optString("name", "").takeIf { it.isNotEmpty() }
            ?: return """{"error":"创建分组时必须指定 name"}"""
        val checklistName = params.optString("checklist", "").takeIf { it.isNotEmpty() }
            ?: return """{"error":"创建分组时必须指定 checklist"}"""

        val checklistId = resolveChecklistId(checklistName, core)
            ?: return """{"error":"未找到名称包含「$checklistName」的清单"}"""

        val color = params.optString("color", "").takeIf { it.isNotEmpty() }
        val group = core.createGroup(name, checklistId, color)
        return JSONObject().apply {
            put("success", true)
            put("group", groupToJson(group))
        }.toString()
    }

    // ═══════════════════════════════════════════════════════════════
    // 批量创建
    // ═══════════════════════════════════════════════════════════════

    /**
     * 批量创建实体。
     * 外层 checklist/group/parent 作为默认值，item 未指定时自动继承。
     * 部分失败时返回成功和失败各自的计数及错误详情，不会中断整个批次。
     */
    private fun executeBatchCreate(entityType: String, items: org.json.JSONArray, params: JSONObject, core: NionCore): String {
        val results = org.json.JSONArray()
        var successCount = 0
        var failCount = 0

        // 外层参数作为批量默认值
        val outerChecklist = params.optString("checklist", "").takeIf { it.isNotEmpty() }
        val outerGroup = params.optString("group", "").takeIf { it.isNotEmpty() }
        val outerParent = params.optString("parent", "").takeIf { it.isNotEmpty() }

        for (i in 0 until items.length()) {
            val itemParams = items.getJSONObject(i)
            try {
                when (entityType) {
                    // task 与 note 走同一段逻辑，只差写入的 kind（4.2 通道统一）
                    "task", "note" -> {
                        val isNote = entityType == "note"
                        val name = itemParams.optString("name", "").takeIf { it.isNotEmpty() }
                            ?: throw IllegalArgumentException(
                                "批量创建${if (isNote) "笔记" else "任务"}时每项必须包含 name，第 ${i + 1} 项缺失"
                            )
                        // item 有值用 item 的，没有则 fallback 到外层参数
                        val checklistName = itemParams.optString("checklist", "").takeIf { it.isNotEmpty() } ?: outerChecklist
                        val groupName = itemParams.optString("group", "").takeIf { it.isNotEmpty() } ?: outerGroup
                        val parentName = itemParams.optString("parent", "").takeIf { it.isNotEmpty() } ?: outerParent

                        val (ownership, errorMsg) = resolveTaskOwnership(checklistName, groupName, parentName, core)
                        if (ownership == null) throw IllegalArgumentException(errorMsg)

                        val (categoryId, groupId, parentId) = ownership
                        // 解析统一的 reminder 参数
                        val reminderRaw = itemParams.optString("reminder", "").takeIf { it.isNotEmpty() }
                        val (recurrenceRule, recurrenceReminderTime, reminder) = if (reminderRaw != null) {
                            parseReminderParam(reminderRaw)
                        } else {
                            Triple(null, null, null)
                        }
                        // 逐项校验预计时长：批量里某一项错了只让该项失败，不影响其它项
                        val minutes = itemParams.optLongOrNull("expected_minutes")
                            ?: params.optLongOrNull("expected_minutes")
                        if (minutes != null && minutes <= 0) {
                            throw IllegalArgumentException("expected_minutes 必须为正整数（分钟）")
                        }
                        val created = core.createTaskEx(
                            name = name,
                            description = itemParams.optString("description", "").takeIf { it.isNotEmpty() },
                            priority = itemParams.optString("priority", "medium"),
                            categoryId = categoryId,
                            parentId = parentId,
                            groupId = groupId,
                            recurrenceRule = recurrenceRule,
                            recurrenceReminderTime = recurrenceReminderTime,
                            reminder = reminder,
                            kind = if (isNote) "note" else "task",
                            expectedMinutes = minutes,
                        )
                        // 键名跟随 entity_type：note 通道给 note，task 通道给 task，
                        // 两个键同时给出会让调用方难以判断究竟建成了什么
                        val key = if (isNote) "note" else "task"
                        results.put(JSONObject().apply {
                            put("index", i)
                            put("success", true)
                            put(key, taskToJson(created))
                        })
                        successCount++
                    }
                    "checklist" -> {
                        val name = itemParams.optString("name", "").takeIf { it.isNotEmpty() }
                            ?: throw IllegalArgumentException("批量创建清单时每项必须包含 name，第 ${i + 1} 项缺失")
                        // 批量创建同样支持清单类型，未指定时默认 task（任务型清单）
                        val itemType = itemParams.optString("checklist_type", "task")
                        val checklist = if (itemType == "notebook") {
                            core.createChecklistWithType(name, "notebook")
                        } else {
                            core.createChecklist(name)
                        }
                        results.put(JSONObject().apply {
                            put("index", i)
                            put("success", true)
                            put("checklist", checklistToJson(checklist))
                        })
                        successCount++
                    }
                    "group" -> {
                        val name = itemParams.optString("name", "").takeIf { it.isNotEmpty() }
                            ?: throw IllegalArgumentException("批量创建分组时每项必须包含 name，第 ${i + 1} 项缺失")
                        val checklistName = itemParams.optString("checklist", "").takeIf { it.isNotEmpty() } ?: outerChecklist
                            ?: throw IllegalArgumentException("批量创建分组时每项必须包含 checklist，第 ${i + 1} 项缺失")
                        val checklistId = resolveChecklistId(checklistName, core)
                            ?: throw IllegalArgumentException("未找到名称包含「$checklistName」的清单")
                        val color = itemParams.optString("color", "").takeIf { it.isNotEmpty() }
                        val group = core.createGroup(name, checklistId, color)
                        results.put(JSONObject().apply {
                            put("index", i)
                            put("success", true)
                            put("group", groupToJson(group))
                        })
                        successCount++
                    }
                    else -> throw IllegalArgumentException("不支持的批量创建类型: $entityType")
                }
            } catch (e: Exception) {
                results.put(JSONObject().apply {
                    put("index", i)
                    put("success", false)
                    put("error", e.message ?: "未知错误")
                })
                failCount++
            }
        }

        return JSONObject().apply {
            put("success", failCount == 0)
            put("entity_type", entityType)
            put("success_count", successCount)
            put("fail_count", failCount)
            put("results", results)
        }.toString()
    }
}

/**
 * 统一更新工具 —— 名称驱动，同时支持字段修改和归属移动。
 *
 * 用 name 按名称定位实体，用 new_name 改名，用 checklist/group/parent 变更归属。
 * 支持批量更新：传 names 数组可一次更新多个同类型实体。
 *
 * 归属变更规则：
 * - checklist：将任务移到目标清单（清除旧分组），或将分组移到目标清单
 * - group：将任务移到目标分组（自动继承分组的清单归属）
 * - parent：将任务挂到目标父任务下成为子任务，传空字符串提升为独立主任务
 * - group 优先级高于 checklist（group 隐含了所属清单）
 *
 * Agent 场景示例：
 * - "把任务标为完成" → entity_type="task", name="学习数学", status="done"
 * - "改一下提醒时间" → entity_type="task", name="学习数学", reminder="2026-12-31T09:00"
 * - "把任务移到工作清单" → entity_type="task", name="学习数学", checklist="工作"
 * - "把任务移到语文分组" → entity_type="task", name="学习数学", group="语文"
 * - "变成子任务" → entity_type="task", name="第一章", parent="学习数学"
 * - "提升为主任务" → entity_type="task", name="第一章", parent=""
 * - "清单改名" → entity_type="checklist", name="学习", new_name="学习计划"
 * - "分组移到工作清单" → entity_type="group", name="语文", checklist="工作"
 * - "分组改颜色" → entity_type="group", name="语文", color="#4CAF50"
 */
object UpdateTool : Tool {
    override val name = "update"
    override val affectsData = setOf(DataType.TASK_DATA)
    override val description =
        "更新实体。优先用id/ids精确定位，回退name/names。" +
            "checklist/group/parent 变更归属，kind 在 task/note 之间互转。" +
            "支持批量。entity_type=note 时 append=true 可追加内容。" +
            "笔记与任务是同一种实体的两种 kind，可以互转，区别只是能否勾选完成。"

    private val schema = """
    {
        "type": "object",
        "properties": {
            "entity_type": {
                "type": "string",
                "enum": ["task", "checklist", "group", "note"]
            },
            "id": { "type": "string", "description": "按 ID 定位单个实体，跳过名称解析" },
            "ids": {
                "type": "array",
                "items": { "type": "string" },
                "description": "按 ID 数组批量更新，跳过名称解析"
            },
            "name": { "type": "string", "description": "按名称定位，精确优先包含兜底" },
            "names": {
                "type": "array",
                "items": { "type": "string" },
                "description": "按名称数组批量更新"
            },
            "new_name": { "type": "string", "description": "新名称，用于改名" },
            "description": { "type": "string", "description": "任务描述或笔记正文" },
            "append": { "type": "boolean", "description": "仅entity_type=note有效：true=追加内容到末尾，false=替换全部" },
            "priority": {
                "type": "string",
                "enum": ["low", "medium", "high"],
                "description": "优先级"
            },
            "status": {
                "type": "string",
                "enum": ["todo", "in_progress", "done"],
                "description": "状态"
            },
            "checklist": { "type": "string", "description": "将任务移到目标清单，按名称匹配，不是范围筛选" },
            "group": { "type": "string", "description": "将任务移到目标分组，按名称匹配，自动继承分组的清单" },
            "parent": { "type": "string", "description": "挂到目标父任务下，按名称匹配，传空字符串清除父任务" },
            "kind": {
                "type": "string",
                "enum": ["task", "note"],
                "description": "实体类型互转：task=可勾选的任务，note=笔记。转换不会丢失内容、关联与专注记录"
            },
            "expected_minutes": {
                "type": "integer",
                "description": "预计总工作量（分钟），正整数。未规划时不要填，不要猜"
            },
            "clear_expected_minutes": {
                "type": "boolean",
                "description": "true=清除预计时长"
            },
            "clear_reminder": {
                "type": "boolean",
                "description": "true=清除一次性提醒"
            },
            "reminder": {
                "type": "string",
                "description": "提醒时间，HH:MM每日循环，YYYY-MM-DDTHH:MM一次性"
            },
            "color": { "type": "string", "description": "分组颜色如#FF5722，仅entity_type=group有效" }
        },
        "required": ["entity_type"]
    }
    """.trimIndent()

    override fun parametersSchema(): JSONObject = JSONObject(schema)

    override suspend fun execute(params: JSONObject, core: NionCore): String {
        val entityType = params.getString("entity_type")

        // note 类型复用 task 的更新逻辑（笔记与任务是同一实体的两种 kind）
        val effectiveType = if (entityType == "note") "task" else entityType

        // ── ID 路径：优先级最高，跳过所有名称解析 ──
        val idsArray = params.optJSONArray("ids")
        if (idsArray != null && idsArray.length() > 0) {
            return executeBatchUpdateByIds(effectiveType, idsArray, params, core)
        }

        val singleId = params.optString("id", "").takeIf { it.isNotEmpty() }
        if (singleId != null) {
            return when (effectiveType) {
                // 显式指定 id 时不写 kind：避免「调用方只是在改标题」却被默认改成 task
                "task" -> executeUpdateTask("", params, core, resolvedId = singleId, kindHint = null)
                "checklist" -> executeUpdateChecklist("", params, core, resolvedId = singleId)
                "group" -> executeUpdateGroup("", params, core, resolvedId = singleId)
                else -> """{"error":"不支持的 entity_type: $entityType"}"""
            }
        }

        // ── 名称路径：原有逻辑 ──
        val namesArray = params.optJSONArray("names")
        if (namesArray != null && namesArray.length() > 0) {
            return executeBatchUpdate(effectiveType, namesArray, params, core)
        }

        val name = params.optString("name", "").takeIf { it.isNotEmpty() }
            ?: return """{"error":"更新操作必须指定 id、ids、name 或 names"}"""

        // 名称路径下 entity_type=note 可作为 kind 提示（用户说「把这条笔记……」）。
        // 但如果参数里已经有显式 kind，以显式为准（fromParams 内部处理优先级）。
        return when (effectiveType) {
            "task" -> executeUpdateTask(name, params, core, kindHint = if (entityType == "note") "note" else null)
            "checklist" -> executeUpdateChecklist(name, params, core)
            "group" -> executeUpdateGroup(name, params, core)
            else -> """{"error":"不支持的 entity_type: $entityType"}"""
        }
    }

    /**
     * 批量更新实体。
     * 遍历 names 数组，逐个解析名称为 ID，应用相同的变更字段和归属变更。
     * 部分失败时返回各自的 success/fail，不中断整个批次。
     */
    private fun executeBatchUpdate(entityType: String, names: org.json.JSONArray, params: JSONObject, core: NionCore): String {
        val results = org.json.JSONArray()
        var successCount = 0
        var failCount = 0

        for (i in 0 until names.length()) {
            val name = names.getString(i)
            try {
                when (entityType) {
                    "task" -> {
                        val result = executeUpdateTask(name, params, core)
                        val json = JSONObject(result)
                        if (!json.optBoolean("success", false)) throw IllegalArgumentException(json.optString("error", "未知错误"))
                        results.put(JSONObject().apply {
                            put("name", name)
                            put("success", true)
                            put("task", json.optJSONObject("task"))
                        })
                        successCount++
                    }
                    "checklist" -> {
                        val matched = resolveChecklistsByName(name, core)
                        if (matched.isEmpty()) throw IllegalArgumentException("未找到名称包含「$name」的清单")
                        val newName = params.optString("new_name", "").takeIf { it.isNotEmpty() }
                            ?: throw IllegalArgumentException("批量更新清单时必须指定 new_name")
                        val checklist = core.updateChecklistName(matched.first().id, newName)
                        results.put(JSONObject().apply {
                            put("name", name)
                            put("success", true)
                            put("checklist", checklistToJson(checklist))
                        })
                        successCount++
                    }
                    "group" -> {
                        val result = executeUpdateGroup(name, params, core)
                        val json = JSONObject(result)
                        if (!json.optBoolean("success", false)) throw IllegalArgumentException(json.optString("error", "未知错误"))
                        results.put(JSONObject().apply {
                            put("name", name)
                            put("success", true)
                            put("group", json.optJSONObject("group"))
                        })
                        successCount++
                    }
                    else -> throw IllegalArgumentException("不支持的批量更新类型: $entityType")
                }
            } catch (e: Exception) {
                results.put(JSONObject().apply {
                    put("name", name)
                    put("success", false)
                    put("error", e.message ?: "未知错误")
                })
                failCount++
            }
        }

        return JSONObject().apply {
            put("success", failCount == 0)
            put("entity_type", entityType)
            put("success_count", successCount)
            put("fail_count", failCount)
            put("results", results)
        }.toString()
    }

    /**
     * 按 ID 数组批量更新实体。
     * 跳过名称解析，逐个用 ID 调用对应的更新方法，应用相同的变更字段。
     * 部分失败时返回各自的 success/fail，不中断整个批次。
     */
    private fun executeBatchUpdateByIds(entityType: String, ids: org.json.JSONArray, params: JSONObject, core: NionCore): String {
        val results = org.json.JSONArray()
        var successCount = 0
        var failCount = 0

        for (i in 0 until ids.length()) {
            val id = ids.getString(i)
            try {
                val result: String = when (entityType) {
                    "task" -> executeUpdateTask("", params, core, resolvedId = id)
                    "checklist" -> executeUpdateChecklist("", params, core, resolvedId = id)
                    "group" -> executeUpdateGroup("", params, core, resolvedId = id)
                    else -> throw IllegalArgumentException("不支持的批量更新类型: $entityType")
                }
                val json = JSONObject(result)
                if (!json.optBoolean("success", false)) throw IllegalArgumentException(json.optString("error", "未知错误"))
                // 从返回结果中提取实体对象
                val entityKey = when (entityType) {
                    "task" -> "task"
                    "checklist" -> "checklist"
                    "group" -> "group"
                    else -> null
                }
                results.put(JSONObject().apply {
                    put("id", id)
                    put("success", true)
                    if (entityKey != null) put(entityKey, json.optJSONObject(entityKey))
                })
                successCount++
            } catch (e: Exception) {
                results.put(JSONObject().apply {
                    put("id", id)
                    put("success", false)
                    put("error", e.message ?: "未知错误")
                })
                failCount++
            }
        }

        return JSONObject().apply {
            put("success", failCount == 0)
            put("entity_type", entityType)
            put("success_count", successCount)
            put("fail_count", failCount)
            put("results", results)
        }.toString()
    }

    /**
     * 更新单个实体 —— 字段修改与归属移动一次事务提交。
     *
     * 定位方式：
     * - resolvedId 非空时直接使用 ID，跳过名称解析
     * - 否则用 name 全局解析名称（不做范围限定，重名时报错）
     *
     * 字段解析与执行完全交给 [TaskChangeSet.fromParams] + [applyTaskChangeSet]，
     * 与 ids / names 批量路径共用同一套语义 —— 这是消灭「报错但已写入」的关键：
     * 旧实现在这里分四次调用（parent → group → clear → update），只改归属就会被
     * updateTask 的 `No fields to update` 打成假失败。
     *
     * @param name        实体名称（resolvedId 为空时用于名称解析）
     * @param params      参数对象
     * @param core        NionCore 单例
     * @param resolvedId  预解析的实体 ID，非空时跳过名称解析
     * @param kindHint    实体类型提示（entity_type=note 时传 "note"），null 表示不写 kind
     */
    private fun executeUpdateTask(
        name: String,
        params: JSONObject,
        core: NionCore,
        resolvedId: String? = null,
        kindHint: String? = null,
    ): String {
        // ID 定位：resolvedId 非空时直接使用；否则走名称解析
        val id: String
        if (resolvedId != null) {
            id = resolvedId
        } else {
            val (resolved, err) = resolveTaskIdByName(name, null, null, core)
            if (resolved == null) return err
            id = resolved
        }

        // 参数 → 变更集（含归属名称解析）。解析失败时零写入
        val (change, parseErr) = parseTaskChangeSet(params, core, kindHint)
        if (change == null) return parseErr

        // 一个字段都没给：明确指出问题，而不是静默「成功」
        if (change.isEmpty) {
            return """{"error":"没有需要更新的字段，请至少提供 new_name/description/priority/status/kind/checklist/group/parent/reminder 之一"}"""
        }

        val (task, applyErr) = applyTaskChangeSet(id, change, core)
        if (task == null) return applyErr

        return JSONObject().apply {
            put("success", true)
            // 同时给出 task 与 note 两个键：调用方按 entity_type 取值，
            // 而实际 kind 可能与 entity_type 不同（互转场景），两个键都指向同一实体
            put("task", taskToJson(task))
            put("note", taskToJson(task))
        }.toString()
    }

    /**
     * 更新清单名称。
     *
     * @param name        清单名称（resolvedId 为空时用于名称解析）
     * @param params      参数对象
     * @param core        NionCore 单例
     * @param resolvedId  预解析的清单 ID，非空时跳过名称解析
     */
    private fun executeUpdateChecklist(name: String, params: JSONObject, core: NionCore, resolvedId: String? = null): String {
        val newName = params.optString("new_name", "").takeIf { it.isNotEmpty() }
            ?: return """{"error":"更新清单时必须指定 new_name"}"""

        val id: String
        if (resolvedId != null) {
            id = resolvedId
        } else {
            val matched = resolveChecklistsByName(name, core)
            if (matched.isEmpty()) {
                return """{"error":"未找到名称包含「$name」的清单"}"""
            }
            if (matched.size > 1) {
                return """{"error":"找到多个名称包含「$name」的清单，请用更精确的名称"}"""
            }
            id = matched[0].id
        }

        val checklist = core.updateChecklistName(id, newName)
        return JSONObject().apply {
            put("success", true)
            put("checklist", checklistToJson(checklist))
        }.toString()
    }

    /**
     * 更新分组 —— 支持改名、改颜色、移动到其他清单。
     *
     * @param name        分组名称（resolvedId 为空时用于名称解析）
     * @param params      参数对象
     * @param core        NionCore 单例
     * @param resolvedId  预解析的分组 ID，非空时跳过名称解析
     */
    private fun executeUpdateGroup(name: String, params: JSONObject, core: NionCore, resolvedId: String? = null): String {
        val newName = params.optString("new_name", "").takeIf { it.isNotEmpty() }
        val moveChecklist = params.optString("checklist", "").takeIf { it.isNotEmpty() }
        val color = params.optString("color", "").takeIf { it.isNotEmpty() }

        // ID 定位：resolvedId 非空时直接使用；否则走名称解析
        val id: String
        if (resolvedId != null) {
            id = resolvedId
        } else {
            val (resolved, err) = resolveGroupIdByName(name, null, core)
            if (resolved == null) return err
            id = resolved
        }

        // 归属变更：移到目标清单
        if (moveChecklist != null) {
            val matchedChecklists = resolveChecklistsByName(moveChecklist, core)
            if (matchedChecklists.isEmpty()) return """{"error":"未找到名称包含「$moveChecklist」的清单"}"""
            if (matchedChecklists.size > 1) return """{"error":"找到多个名称包含「$moveChecklist」的清单"}"""
            // 移动分组，Rust 层会同步更新组内任务的 checklist
            val group = core.moveGroupToChecklist(id, matchedChecklists[0].id)
            // 如果还有其他更新（改名/改色），二次更新
            if (newName != null || color != null) {
                val updated = core.updateGroup(group.id, newName ?: group.name, color)
                return JSONObject().apply {
                    put("success", true)
                    put("group", groupToJson(updated))
                }.toString()
            }
            return JSONObject().apply {
                put("success", true)
                put("group", groupToJson(group))
            }.toString()
        }

        // 没有归属变更，只更新名称/颜色
        if (newName == null && color == null) {
            return """{"error":"更新分组时必须至少指定 new_name、checklist 或 color 之一"}"""
        }
        val group = core.updateGroup(id, newName ?: core.getGroup(id).name, color)
        return JSONObject().apply {
            put("success", true)
            put("group", groupToJson(group))
        }.toString()
    }
}

/**
 * 统一删除工具 —— 支持 ID 直接定位和名称模糊定位。
 *
 * 优先使用 ID 定位（精确、无歧义），ID 不可用时回退到名称 + 范围限定。
 * 支持批量删除：传 ids 或 names 数组可一次删除多个同类型实体。
 * 不可撤销，删除前 AI 应确认。
 *
 * 执行优先级：ids → id → names → name
 *
 * Agent 场景示例：
 * - "删掉学习数学这个任务" → entity_type="task", name="学习数学"
 * - "删掉这三个任务" → entity_type="task", ids=["id1","id2","id3"]
 * - "删除学习清单" → entity_type="checklist", id="清单ID"
 * - "删掉语文分组" → entity_type="group", name="语文", checklist="学习"
 */
object DeleteTool : Tool {
    override val name = "delete"
    override val affectsData = setOf(DataType.TASK_DATA)
    override val description = "删除实体。优先用id/ids精确定位，回退name/names+checklist/group。支持批量。不可撤销。entity_type=note等同于task（笔记本质是任务）。"

    private val schema = """
    {
        "type": "object",
        "properties": {
            "entity_type": {
                "type": "string",
                "enum": ["task", "checklist", "group", "note"]
            },
            "id": { "type": "string", "description": "按 ID 定位单个实体，跳过名称解析" },
            "ids": {
                "type": "array",
                "items": { "type": "string" },
                "description": "按 ID 数组批量删除，跳过名称解析"
            },
            "name": { "type": "string", "description": "按名称定位，精确优先包含兜底" },
            "names": {
                "type": "array",
                "items": { "type": "string" },
                "description": "按名称数组批量删除"
            },
            "checklist": { "type": "string", "description": "按清单名称限定范围，用于定位task和group" },
            "group": { "type": "string", "description": "按分组名称限定范围，用于定位task" }
        },
        "required": ["entity_type"]
    }
    """.trimIndent()

    override fun parametersSchema(): JSONObject = JSONObject(schema)

    override suspend fun execute(params: JSONObject, core: NionCore): String {
        val entityType = params.getString("entity_type")
        // note 映射为 task（笔记本质是任务，删除逻辑完全相同）
        val effectiveType = if (entityType == "note") "task" else entityType

        // ── ID 路径：优先级最高，跳过所有名称解析 ──
        val idsArray = params.optJSONArray("ids")
        if (idsArray != null && idsArray.length() > 0) {
            return executeBatchDeleteByIds(effectiveType, idsArray, core)
        }

        val singleId = params.optString("id", "").takeIf { it.isNotEmpty() }
        if (singleId != null) {
            return executeSingleDeleteById(effectiveType, singleId, core)
        }

        // ── 名称路径：原有逻辑 ──
        val namesArray = params.optJSONArray("names")
        if (namesArray != null && namesArray.length() > 0) {
            return executeBatchDelete(effectiveType, namesArray, params, core)
        }

        val name = params.optString("name", "").takeIf { it.isNotEmpty() }
            ?: return """{"error":"删除操作必须指定 id、ids、name 或 names"}"""

        return executeSingleDelete(effectiveType, name, params, core)
    }

    /**
     * 按 ID 直接删除单个实体。
     * 跳过名称解析，直接调用 Rust 删除方法。
     * 返回结果中包含 ID，供闹钟取消等后续处理使用。
     */
    private fun executeSingleDeleteById(entityType: String, id: String, core: NionCore): String {
        val deleted = when (entityType) {
            "task" -> core.deleteTask(id)
            "checklist" -> core.deleteChecklist(id)
            "group" -> core.deleteGroup(id)
            else -> return """{"error":"不支持的 entity_type: $entityType"}"""
        }

        return JSONObject().apply {
            put("success", true)
            put("deleted", deleted)
            put("id", id)
        }.toString()
    }

    /**
     * 按 ID 数组批量删除实体。
     * 跳过名称解析，逐个调用 Rust 删除方法。
     * 部分失败时不中断整个批次。
     */
    private fun executeBatchDeleteByIds(entityType: String, ids: org.json.JSONArray, core: NionCore): String {
        val results = org.json.JSONArray()
        var successCount = 0
        var failCount = 0

        for (i in 0 until ids.length()) {
            val id = ids.getString(i)
            try {
                when (entityType) {
                    "task" -> core.deleteTask(id)
                    "checklist" -> core.deleteChecklist(id)
                    "group" -> core.deleteGroup(id)
                    else -> throw IllegalArgumentException("不支持的批量删除类型: $entityType")
                }
                results.put(JSONObject().apply {
                    put("id", id)
                    put("success", true)
                })
                successCount++
            } catch (e: Exception) {
                results.put(JSONObject().apply {
                    put("id", id)
                    put("success", false)
                    put("error", e.message ?: "未知错误")
                })
                failCount++
            }
        }

        return JSONObject().apply {
            put("success", failCount == 0)
            put("entity_type", entityType)
            put("success_count", successCount)
            put("fail_count", failCount)
            put("results", results)
        }.toString()
    }

    /**
     * 单个删除实体（名称路径）。
     * 根据实体类型 + 名称解析出 ID，调用 Rust 删除方法。
     * 返回结果中包含解析后的 ID，供闹钟取消等后续处理使用。
     */
    private fun executeSingleDelete(entityType: String, name: String, params: JSONObject, core: NionCore): String {
        val (id, err) = resolveEntityId(entityType, name, params, core)
        if (id == null) return err

        val deleted = when (entityType) {
            "task" -> core.deleteTask(id)
            "checklist" -> core.deleteChecklist(id)
            "group" -> core.deleteGroup(id)
            else -> return """{"error":"不支持的 entity_type: $entityType"}"""
        }

        return JSONObject().apply {
            put("success", true)
            put("deleted", deleted)
            // 返回解析后的 ID，供 extractTaskId 提取以取消闹钟
            put("id", id)
        }.toString()
    }

    /**
     * 批量删除实体（名称路径）。
     * 遍历 names 数组，逐个解析名称为 ID 并删除，收集结果。
     * 部分失败时不中断整个批次。
     */
    private fun executeBatchDelete(entityType: String, names: org.json.JSONArray, params: JSONObject, core: NionCore): String {
        val results = org.json.JSONArray()
        var successCount = 0
        var failCount = 0
        // 外层 checklist/group 作为批量默认范围限定
        val scopeChecklist = params.optString("checklist", "").takeIf { it.isNotEmpty() }
        val scopeGroup = params.optString("group", "").takeIf { it.isNotEmpty() }

        for (i in 0 until names.length()) {
            val name = names.getString(i)
            try {
                val (id, err) = resolveEntityId(entityType, name, scopeChecklist, scopeGroup, core)
                if (id == null) throw IllegalArgumentException(err)
                when (entityType) {
                    "task" -> core.deleteTask(id)
                    "checklist" -> core.deleteChecklist(id)
                    "group" -> core.deleteGroup(id)
                    else -> throw IllegalArgumentException("不支持的批量删除类型: $entityType")
                }
                results.put(JSONObject().apply {
                    put("name", name)
                    put("success", true)
                })
                successCount++
            } catch (e: Exception) {
                results.put(JSONObject().apply {
                    put("name", name)
                    put("success", false)
                    put("error", e.message ?: "未知错误")
                })
                failCount++
            }
        }

        return JSONObject().apply {
            put("success", failCount == 0)
            put("entity_type", entityType)
            put("success_count", successCount)
            put("fail_count", failCount)
            put("results", results)
        }.toString()
    }

    /**
     * 通用实体名称 → ID 解析。
     * 根据 entity_type 路由到对应的名称解析函数。
     *
     * @param entityType    实体类型
     * @param name          实体名称
     * @param params        参数对象，从中提取 checklist/group 范围限定
     * @param core          NionCore 单例
     * @return 成功时 Pair<ID, "">，失败时 Pair<null, 错误JSON>
     */
    private fun resolveEntityId(
        entityType: String, name: String, params: JSONObject, core: NionCore
    ): Pair<String?, String> {
        val checklistName = params.optString("checklist", "").takeIf { it.isNotEmpty() }
        val groupName = params.optString("group", "").takeIf { it.isNotEmpty() }
        return resolveEntityId(entityType, name, checklistName, groupName, core)
    }

    /**
     * 通用实体名称 → ID 解析（重载，接受显式范围参数）。
     * 供批量操作复用，避免重复解析 params。
     */
    private fun resolveEntityId(
        entityType: String, name: String, checklistName: String?, groupName: String?, core: NionCore
    ): Pair<String?, String> {
        return when (entityType) {
            "task" -> resolveTaskIdByName(name, checklistName, groupName, core)
            "checklist" -> {
                val matched = resolveChecklistsByName(name, core)
                when {
                    matched.isEmpty() -> null to """{"error":"未找到名称包含「$name」的清单"}"""
                    matched.size > 1 -> null to """{"error":"找到多个名称包含「$name」的清单，请用更精确的名称"}"""
                    else -> matched[0].id to ""
                }
            }
            "group" -> resolveGroupIdByName(name, checklistName, core)
            else -> null to """{"error":"不支持的 entity_type: $entityType"}"""
        }
    }
}

/**
 * 排序工具 —— 支持 ID 直接排序和名称解析排序。
 *
 * 优先使用 ordered_ids（精确、无歧义），ID 不可用时回退到 ordered_names + 范围限定。
 * 支持任务、清单、分组三种实体类型。
 *
 * Agent 场景示例：
 * - "按优先级排一下任务" → action="reorder", entity_type="task", ordered_ids=["id1","id2","id3"]
 * - "调整清单顺序" → action="reorder", entity_type="checklist", ordered_names=["工作","学习","生活"]
 * - "分组排序" → action="reorder", entity_type="group", ordered_names=["语文","数学","英语"], checklist="学习"
 */
object ManageTool : Tool {
    override val name = "manage"
    override val affectsData = setOf(DataType.TASK_DATA)
    override val description = "排序和关联操作。action=reorder排序，action=link/unlink关联笔记和任务。优先用ordered_ids/note_id/task_id精确定位，回退ordered_names/note_name/task_name。"

    private val schema = """
    {
        "type": "object",
        "properties": {
            "action": {
                "type": "string",
                "enum": ["reorder", "link", "unlink"]
            },
            "entity_type": {
                "type": "string",
                "enum": ["task", "checklist", "group"]
            },
            "ordered_ids": {
                "type": "array",
                "items": { "type": "string" },
                "description": "按 ID 数组直接排序，跳过名称解析"
            },
            "ordered_names": {
                "type": "array",
                "items": { "type": "string" },
                "description": "按名称数组排序，精确优先包含兜底"
            },
            "checklist": { "type": "string", "description": "按清单名称限定范围，用于解析task和group" },
            "group": { "type": "string", "description": "按分组名称限定范围，用于解析task" },
            "note_id": { "type": "string", "description": "link/unlink：笔记ID" },
            "note_name": { "type": "string", "description": "link/unlink：按名称查找笔记" },
            "task_id": { "type": "string", "description": "link/unlink：任务ID" },
            "task_name": { "type": "string", "description": "link/unlink：按名称查找任务" }
        },
        "required": ["action"]
    }
    """.trimIndent()

    override fun parametersSchema(): JSONObject = JSONObject(schema)

    override suspend fun execute(params: JSONObject, core: NionCore): String {
        val action = params.getString("action")

        return when (action) {
            "reorder" -> executeReorder(params, core)
            "link" -> executeLink(params, core, unlink = false)
            "unlink" -> executeLink(params, core, unlink = true)
            else -> """{"error":"不支持的 action: $action"}"""
        }
    }

    /**
     * 关联/取消关联笔记和任务（双向写入）。
     * 笔记和任务都通过 ID 或名称定位，名称解析使用 searchNotes / getTasks。
     *
     * @param params 参数对象，包含 note_id/note_name 和 task_id/task_name
     * @param core NionCore 单例
     * @param unlink true=取消关联，false=建立关联
     */
    private fun executeLink(params: JSONObject, core: NionCore, unlink: Boolean): String {
        // ── 解析笔记 ID ──
        val noteId = params.optString("note_id", "").takeIf { it.isNotEmpty() }
            ?: run {
                // 按名称搜索笔记
                val noteName = params.optString("note_name", "").takeIf { it.isNotEmpty() }
                    ?: return """{"error":"link/unlink 操作必须指定 note_id 或 note_name"}"""
                // 按名称搜索笔记：include_children=true，子笔记也能被关联到
                val notes = core.searchNotes(noteName, null, true)
                notes.firstOrNull()?.id
                    ?: return """{"error":"未找到名称包含「$noteName」的笔记"}"""
            }

        // ── 解析任务 ID ──
        val taskId = params.optString("task_id", "").takeIf { it.isNotEmpty() }
            ?: run {
                val taskName = params.optString("task_name", "").takeIf { it.isNotEmpty() }
                    ?: return """{"error":"link/unlink 操作必须指定 task_id 或 task_name"}"""
                val allTasks = core.getTasks()
                val exact = allTasks.filter { it.name == taskName }
                val matched = if (exact.isNotEmpty()) exact else allTasks.filter { it.name.contains(taskName) }
                matched.firstOrNull()?.id
                    ?: return """{"error":"未找到名称包含「$taskName」的任务"}"""
            }

        // ── 执行关联/取消关联 ──
        if (unlink) {
            core.unlinkNoteFromTask(noteId, taskId)
        } else {
            core.linkNoteToTask(noteId, taskId)
        }

        return JSONObject().apply {
            put("success", true)
            put("action", if (unlink) "unlink" else "link")
            put("note_id", noteId)
            put("task_id", taskId)
            put("message", if (unlink) "已取消笔记与任务的关联" else "已关联笔记与任务")
        }.toString()
    }

    /**
     * 自由排序实体。
     *
     * 优先使用 ordered_ids（跳过名称解析），回退到 ordered_names（逐个解析为 ID）。
     * Rust 层按数组索引设置 sort_order。
     *
     * @param params 参数对象
     * @param core   NionCore 单例
     * @return 排序结果的 JSON 字符串
     */
    private fun executeReorder(params: JSONObject, core: NionCore): String {
        // 校验 entity_type
        val entityType = params.optString("entity_type", "").takeIf { it.isNotEmpty() }
            ?: return """{"error":"reorder 操作必须指定 entity_type（task/checklist/group）"}"""
        if (entityType !in listOf("task", "checklist", "group")) {
            return """{"error":"不支持的 entity_type: $entityType，可选值：task, checklist, group"}"""
        }

        // ── ID 路径：优先使用 ordered_ids ──
        val orderedIdsArray = params.optJSONArray("ordered_ids")
        if (orderedIdsArray != null && orderedIdsArray.length() > 0) {
            val orderedIds = (0 until orderedIdsArray.length()).map { orderedIdsArray.getString(it) }
            return executeReorderByIds(entityType, orderedIds, core)
        }

        // ── 名称路径：ordered_names ──
        val orderedNamesArray = params.optJSONArray("ordered_names")
            ?: return """{"error":"reorder 操作必须指定 ordered_ids 或 ordered_names"}"""
        if (orderedNamesArray.length() == 0) {
            return """{"error":"ordered_names 不能为空，至少需要一个名称"}"""
        }

        // 范围限定参数
        val checklistScope = params.optString("checklist", "").takeIf { it.isNotEmpty() }
        val groupScope = params.optString("group", "").takeIf { it.isNotEmpty() }

        // 将名称数组解析为 ID 数组
        val orderedIds = mutableListOf<String>()
        for (i in 0 until orderedNamesArray.length()) {
            val name = orderedNamesArray.getString(i)
            val (id, err) = when (entityType) {
                "task" -> resolveTaskIdByName(name, checklistScope, groupScope, core)
                "checklist" -> {
                    val matched = resolveChecklistsByName(name, core)
                    when {
                        matched.isEmpty() -> null to """{"error":"排序失败：未找到名称包含「$name」的清单"}"""
                        matched.size > 1 -> null to """{"error":"排序失败：找到多个名称包含「$name」的清单"}"""
                        else -> matched[0].id to ""
                    }
                }
                "group" -> resolveGroupIdByName(name, checklistScope, core)
                else -> null to """{"error":"不支持的排序类型: $entityType"}"""
            }
            if (id == null) return err
            orderedIds.add(id)
        }

        return executeReorderByIds(entityType, orderedIds, core)
    }

    /**
     * 按 ID 数组调用 Rust 排序方法。
     * 供 ordered_ids 直接路径和 ordered_names 解析后的路径共用。
     */
    private fun executeReorderByIds(entityType: String, orderedIds: List<String>, core: NionCore): String {
        try {
            when (entityType) {
                "task" -> core.reorderTasks(orderedIds)
                "checklist" -> core.reorderChecklists(orderedIds)
                "group" -> core.reorderGroups(orderedIds)
            }
        } catch (e: Exception) {
            return """{"error":"排序失败：${e.message ?: "未知错误"}"}"""
        }

        val typeLabel = when (entityType) {
            "task" -> "任务"
            "checklist" -> "清单"
            "group" -> "分组"
            else -> entityType
        }

        return JSONObject().apply {
            put("success", true)
            put("entity_type", entityType)
            put("ordered_count", orderedIds.size)
            put("message", "已按指定顺序排列 ${orderedIds.size} 个${typeLabel}")
        }.toString()
    }
}
