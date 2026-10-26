package com.echonion.nion.ui.companion.tools

import com.echonion.nion.ui.task.TimeWindows
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import uniffi.nion_core.NionCore

/**
 * 排期工具 —— 把任务排进每日时间窗，先给建议、确认后才写库（4.3）。
 *
 * 诚实性约定：
 * - 默认 confirm=false，只返回建议，绝不写库；
 * - 缺 expected_minutes 的任务进 unplaced 并说明「缺少 expected_minutes」，不猜时长；
 * - 部分写入失败逐项报告，不整体标成功；
 * - assignments 携带稳定 task id 与 originalReminder，供 UI 做过期校验；
 *   模型不需要、也不能靠标题去匹配任务。
 */
object ScheduleTool : Tool {
    override val name = "schedule"
    override val affectsData = setOf(DataType.TASK_DATA)
    override val description =
        "把任务排进每日时间窗，返回排期建议。默认只预览不写库；" +
            "用户确认后再次调用并传 confirm=true 才真正修改任务的提醒时间。" +
            "缺少预计时长的任务会被列入未排入，请不要自己编造时长。"

    private val schema = """
    {
        "type": "object",
        "properties": {
            "tasks": {
                "type": "array",
                "description": "需要整块安排的任务",
                "items": {
                    "type": "object",
                    "properties": {
                        "name": { "type": "string" },
                        "id": { "type": "string" },
                        "expected_minutes": { "type": "integer" }
                    }
                }
            },
            "fragmented": {
                "type": "array",
                "description": "可碎片化的任务，如每天刷题 60 分钟，可拆 30 分钟块",
                "items": {
                    "type": "object",
                    "properties": {
                        "name": { "type": "string" },
                        "id": { "type": "string" },
                        "minutes_per_day": { "type": "integer" },
                        "chunk_minutes": { "type": "integer" }
                    }
                }
            },
            "time_windows": {
                "type": "array",
                "description": "每日可用时段；不传则读取设置里的每日时间窗",
                "items": {
                    "type": "object",
                    "properties": {
                        "start": { "type": "string" },
                        "end": { "type": "string" }
                    }
                }
            },
            "start_date": { "type": "string", "description": "YYYY-MM-DD，默认今天" },
            "assignments": {
                "type": "array",
                "description": "confirm=true 时必填：要写回的分配项，来自上一次预览结果",
                "items": {
                    "type": "object",
                    "properties": {
                        "id": { "type": "string" },
                        "date": { "type": "string" },
                        "start": { "type": "string" },
                        "end": { "type": "string" },
                        "original_reminder": { "type": "string" }
                    }
                }
            },
            "confirm": {
                "type": "boolean",
                "description": "true = 真正写入提醒时间；默认 false 只预览"
            }
        }
    }
    """.trimIndent()

    override fun parametersSchema(): JSONObject = JSONObject(schema)

    override suspend fun execute(params: JSONObject, core: NionCore): String {
        val confirm = params.optBoolean("confirm", false)

        // ── 已确认：只回写 assignments，不重新算 ──
        // 前端把用户看过的分配项原样传回，避免「确认时又算了一遍、结果和卡片不一致」。
        if (confirm) {
            return applyConfirmed(params, core)
        }

        // ── 预览：解析任务 → 算占用 → 贪心装窗 ──
        val startDate = params.optString("start_date", "").takeIf { it.isNotEmpty() }
            ?.let { runCatching { LocalDate.parse(it, DateTimeFormatter.ISO_LOCAL_DATE) }.getOrNull() }
            ?: LocalDate.now()

        // 时间窗：参数优先，其次读设置
        val windows: List<com.echonion.nion.ui.task.TimeWindow> = params.optJSONArray("time_windows")
            ?.let { arr ->
                buildList {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        add(
                            com.echonion.nion.ui.task.TimeWindow(
                                o.optString("start", ""),
                                o.optString("end", ""),
                            ),
                        )
                    }
                }
            }
            ?: TimeWindows.fromJson(core.getSetting(TimeWindows.SETTING_KEY))

        // 解析待排任务。任一项缺时长/找不到 → 进 unplaced，不中断整批
        val pending = mutableListOf<SchedulePlanner.PendingTask>()
        val unplaced = mutableListOf<SchedulePlanner.Unplaced>()
        val tasksArr = params.optJSONArray("tasks")
        if (tasksArr != null) {
            for (i in 0 until tasksArr.length()) {
                val item = tasksArr.optJSONObject(i) ?: continue
                val resolved = resolveEntity(item, core)
                if (resolved == null) {
                    unplaced += SchedulePlanner.Unplaced(
                        null, item.optString("name", "未知任务"), "找不到该任务",
                    )
                    continue
                }
                // 参数显式给了时长优先；否则查任务字段；都没有 → 不猜
                val minutes = item.optLongOrNull("expected_minutes")
                    ?: resolved.expectedMinutes
                if (minutes == null || minutes <= 0) {
                    unplaced += SchedulePlanner.Unplaced(
                        resolved.id, resolved.name, "缺少 expected_minutes",
                    )
                    continue
                }
                pending += SchedulePlanner.PendingTask(
                    taskId = resolved.id,
                    title = resolved.name,
                    minutes = minutes.toInt(),
                    priority = resolved.priority,
                    originalReminder = resolved.reminder,
                )
            }
        }

        val fragmented = mutableListOf<SchedulePlanner.FragmentedTask>()
        val fragArr = params.optJSONArray("fragmented")
        if (fragArr != null) {
            for (i in 0 until fragArr.length()) {
                val item = fragArr.optJSONObject(i) ?: continue
                val resolved = resolveEntity(item, core)
                val perDay = item.optLongOrNull("minutes_per_day") ?: 0L
                if (perDay <= 0) {
                    unplaced += SchedulePlanner.Unplaced(
                        resolved?.id, item.optString("name", "未知任务"), "缺少 minutes_per_day",
                    )
                    continue
                }
                val chunk = item.optLongOrNull("chunk_minutes") ?: perDay
                fragmented += SchedulePlanner.FragmentedTask(
                    taskId = resolved?.id,
                    title = resolved?.name ?: item.optString("name", "碎片任务"),
                    minutesPerDay = perDay.toInt(),
                    chunkMinutes = chunk.toInt(),
                )
            }
        }

        if (pending.isEmpty() && fragmented.isEmpty() && unplaced.isEmpty()) {
            return """{"error":"没有可排期的任务，请提供 tasks 或 fragmented"}"""
        }

        // 已占用：本次要排的任务自身不算占用（它们的位置本来就要改）
        val excludeIds = (pending.map { it.taskId } + fragmented.mapNotNull { it.taskId }).toSet()
        val busy = SchedulePlanner.busyFromTasks(core.getTasks(), excludeIds, startDate)

        val result = SchedulePlanner.plan(pending, fragmented, windows, startDate, busy)

        return JSONObject().apply {
            put("confirmed", false)
            put("assignments", JSONArray().apply {
                result.assignments.forEach { a ->
                    put(JSONObject().apply {
                        // 稳定 ID 是写回依据；title 只用于显示
                        put("id", a.taskId)
                        put("task", a.title)
                        put("date", a.date.format(DateTimeFormatter.ISO_LOCAL_DATE))
                        put("start", a.startText)
                        put("end", a.endText)
                        put("minutes", a.end - a.start)
                        // 供 UI 做过期校验：预览之后用户可能手动改过时间
                        put("original_reminder", a.originalReminder ?: JSONObject.NULL)
                    })
                }
            })
            put("unplaced", JSONArray().apply {
                (unplaced + result.unplaced).forEach { u ->
                    put(JSONObject().apply {
                        if (u.taskId != null) put("id", u.taskId)
                        put("task", u.title)
                        // 原因必须具体，不能一律写「时间不足」
                        put("reason", u.reason)
                    })
                }
            })
            result.earliest?.let { put("earliest_completion", it.format(DateTimeFormatter.ISO_LOCAL_DATE)) }
            // 时间窗为空时给出可操作的下一步，而不是只报失败
            if (windows.isEmpty()) {
                put("hint", "尚未配置每日时间窗，请到设置 → 任务与日程 → 每日时间窗补充")
            }
        }.toString()
    }

    /**
     * 应用已确认的分配项。
     *
     * 逐项写入并如实报告：部分失败必须返回 partial 语义（success 字段 + 两个列表），
     * 让前端能只重试失败项。
     */
    private fun applyConfirmed(params: JSONObject, core: NionCore): String {
        val arr = params.optJSONArray("assignments")
            ?: return """{"error":"confirm=true 时必须提供 assignments（来自预览结果）"}"""
        if (arr.length() == 0) {
            return """{"error":"assignments 为空，没有可写入的排期"}"""
        }

        val assignments = mutableListOf<SchedulePlanner.Assignment>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id", "").takeIf { it.isNotEmpty() }
                ?: return """{"error":"assignments 第 ${i + 1} 项缺少 id"}"""
            val date = o.optString("date", "").takeIf { it.isNotEmpty() }
                ?: return """{"error":"assignments 第 ${i + 1} 项缺少 date"}"""
            val start = o.optString("start", "").takeIf { it.isNotEmpty() }
                ?: return """{"error":"assignments 第 ${i + 1} 项缺少 start"}"""
            val end = o.optString("end", "").ifEmpty { start }
            val parsedDate = runCatching {
                LocalDate.parse(date, DateTimeFormatter.ISO_LOCAL_DATE)
            }.getOrNull() ?: return """{"error":"assignments 第 ${i + 1} 项 date 格式不正确"}"""
            val startMin = com.echonion.nion.ui.task.TimeWindow.parseMinutes(start)
                ?: return """{"error":"assignments 第 ${i + 1} 项 start 格式不正确"}"""
            val endMin = com.echonion.nion.ui.task.TimeWindow.parseMinutes(end)
                ?: return """{"error":"assignments 第 ${i + 1} 项 end 格式不正确"}"""
            assignments += SchedulePlanner.Assignment(
                taskId = id,
                title = o.optString("task", ""),
                date = parsedDate,
                start = startMin,
                end = endMin,
                originalReminder = o.optString("original_reminder", "").takeIf { it.isNotEmpty() },
            )
        }

        // 过期校验：预览之后用户可能手动改过时间。
        // 有 original_reminder 时比对当前值，不一致则跳过该项并明确标为 stale。
        val stale = mutableListOf<Pair<String, String>>()
        val fresh = mutableListOf<SchedulePlanner.Assignment>()
        for (a in assignments) {
            val current = runCatching { core.getTask(a.taskId).reminder }.getOrNull()
            if (a.originalReminder != null && current != a.originalReminder) {
                stale += a.taskId to "任务时间已变化，需重新排期"
                continue
            }
            fresh += a
        }

        val (applied, errors) = SchedulePlanner.applyAssignments(core, fresh)

        val appliedList = JSONArray().apply { applied.forEach { put(it) } }
        val failedList = JSONArray().apply {
            errors.forEach { (id, msg) -> put(JSONObject().apply { put("id", id); put("reason", msg) }) }
            stale.forEach { (id, msg) -> put(JSONObject().apply { put("id", id); put("reason", msg) }) }
        }

        return JSONObject().apply {
            put("confirmed", true)
            put("applied_count", applied.size)
            put("failed_count", failedList.length())
            put("applied", appliedList)
            put("failed", failedList)
            // 语义诚实：只有真的全成功才算 success
            put("success", applied.size == assignments.size)
            if (applied.isNotEmpty() && failedList.length() > 0) {
                put("status", "partial")
            } else if (applied.isEmpty()) {
                put("status", "failed")
            } else {
                put("status", "applied")
            }
        }.toString()
    }

    /**
     * 按 id 或 name 解析任务实体。id 优先（精确、无歧义）。
     */
    private fun resolveEntity(item: JSONObject, core: NionCore): uniffi.nion_core.TaskData? {
        val id = item.optString("id", "").takeIf { it.isNotEmpty() }
        if (id != null) {
            return runCatching { core.getTask(id) }.getOrNull()
        }
        val name = item.optString("name", "").takeIf { it.isNotEmpty() } ?: return null
        val all = runCatching { core.getTasks() }.getOrDefault(emptyList())
        val exact = all.filter { it.name == name }
        if (exact.size == 1) return exact[0]
        val fuzzy = all.filter { it.name.contains(name) }
        return if (fuzzy.size == 1) fuzzy[0] else null
    }
}
