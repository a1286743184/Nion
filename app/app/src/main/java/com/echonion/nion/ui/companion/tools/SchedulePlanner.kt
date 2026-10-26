package com.echonion.nion.ui.companion.tools

import com.echonion.nion.ui.task.TimeWindow
import com.echonion.nion.ui.task.TimeWindows
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import uniffi.nion_core.NionCore
import uniffi.nion_core.TaskData

/**
 * 排期算法 —— 贪心装窗，把任务塞进每日时间窗的空隙。
 *
 * 设计边界（4.3 定稿，刻意不做的部分）：
 * - 不做约束求解器（依赖关系、场地、人员）；
 * - 不做跨天拆分（单个任务必须一次塞进某个窗口）；
 * - 排期上限 90 天，避免给「无解」任务无限往后找；
 * - 结果是**建议**：只有 confirm=true 才回写 reminder。
 *
 * 为什么单独成文件：算法是纯逻辑（输入任务/窗口/占用，输出分配），
 * 可以脱离 Android 单测；工具层只负责参数解析与结果包装。
 */
object SchedulePlanner {

    /** 排期上限天数 */
    const val MAX_DAYS = 90

    /** 单个时间窗（分钟表示，便于算法计算） */
    data class Window(val startMin: Int, val endMin: Int)

    /**
     * 已占用区间 —— 来自「已有 reminder + expected_minutes」的任务。
     *
     * @param date  日期
     * @param start 开始分钟
     * @param end   结束分钟
     */
    data class Busy(val date: LocalDate, val start: Int, val end: Int)

    /**
     * 一条分配结果。
     *
     * @param taskId           稳定任务 ID（写回依据，不用标题匹配）
     * @param title            仅用于显示
     * @param date             分配日期
     * @param start            开始分钟
     * @param end              结束分钟
     * @param originalReminder 排期前该任务的 reminder 原值，供 UI 做过期校验
     */
    data class Assignment(
        val taskId: String,
        val title: String,
        val date: LocalDate,
        val start: Int,
        val end: Int,
        val originalReminder: String?,
    ) {
        /** "HH:MM" 形式开始时间 */
        val startText: String get() = TimeWindow.formatMinutes(start)
        /** "HH:MM" 形式结束时间 */
        val endText: String get() = TimeWindow.formatMinutes(end)
    }

    /**
     * 未排入的任务及原因 —— 必须如实给出具体原因，不能一律写「时间不足」。
     *
     * @param taskId 任务 ID（可能为 null，表示按名称也没解析到）
     * @param title  任务名
     * @param reason 具体原因
     */
    data class Unplaced(val taskId: String?, val title: String, val reason: String)

    /**
     * 排期结果。
     *
     * @param assignments 成功分配项（按日期、开始时间排序）
     * @param unplaced    未排入项
     * @param earliest    最早完成日期；无分配时为 null（不伪造日期）
     */
    data class Result(
        val assignments: List<Assignment>,
        val unplaced: List<Unplaced>,
        val earliest: LocalDate?,
    )

    /** 待排期任务（已解析出时长） */
    data class PendingTask(
        val taskId: String,
        val title: String,
        val minutes: Int,
        val priority: String,
        val originalReminder: String?,
    )

    /** 可碎片化任务：每天若干分钟，可切成 chunk */
    data class FragmentedTask(
        val taskId: String?,
        val title: String,
        val minutesPerDay: Int,
        val chunkMinutes: Int,
    )

    /** 优先级排序权重（high 先排） */
    private fun priorityWeight(p: String): Int = when (p) {
        "high" -> 0
        "medium" -> 1
        else -> 2
    }

    /**
     * 计算排期。
     *
     * @param tasks      需要整块安排的任务（每个任务一次性占满连续区间）
     * @param fragmented 可碎片化任务
     * @param windows    每日可用时间窗（空表示未配置 → 全部进 unplaced）
     * @param startDate  从哪天开始排
     * @param busy       已有占用（含已排期任务自己，避免重叠）
     */
    fun plan(
        tasks: List<PendingTask>,
        fragmented: List<FragmentedTask>,
        windows: List<TimeWindow>,
        startDate: LocalDate,
        busy: List<Busy>,
    ): Result {
        // 时间窗为空：明确表达为配置问题，而不是「排不下」
        if (windows.isEmpty()) {
            val unplaced = (tasks.map { Unplaced(it.taskId, it.title, "未配置每日时间窗") } +
                fragmented.map { Unplaced(it.taskId, it.title, "未配置每日时间窗") })
            return Result(emptyList(), unplaced, null)
        }

        val windowRanges = windows.mapNotNull { w ->
            val s = w.startMinutes ?: return@mapNotNull null
            val e = w.endMinutes ?: return@mapNotNull null
            if (e > s) Window(s, e) else null
        }
        if (windowRanges.isEmpty()) {
            val unplaced = (tasks.map { Unplaced(it.taskId, it.title, "每日时间窗无效") } +
                fragmented.map { Unplaced(it.taskId, it.title, "每日时间窗无效") })
            return Result(emptyList(), unplaced, null)
        }

        val assignments = mutableListOf<Assignment>()
        val unplaced = mutableListOf<Unplaced>()

        // 已占用按日期分组，便于逐日查空档
        val busyByDate: MutableMap<LocalDate, MutableList<Busy>> =
            busy.groupBy { it.date }.mapValues { it.value.toMutableList() }.toMutableMap()

        /** 找出某天某个长度要求的第一个空档；找不到返回 null */
        fun findSlot(date: LocalDate, minutes: Int): Pair<Int, Int>? {
            val occupied = busyByDate[date].orEmpty().sortedBy { it.start }
            for (w in windowRanges) {
                // 在窗口内滚动寻找可用起点
                var cursor = w.startMin
                while (cursor + minutes <= w.endMin) {
                    // 找出与 [cursor, cursor+minutes) 冲突的占用项，冲突则把游标推到该项结束
                    val conflict = occupied.firstOrNull { it.start < cursor + minutes && it.end > cursor }
                    if (conflict == null) {
                        return cursor to (cursor + minutes)
                    }
                    cursor = maxOf(cursor, conflict.end)
                }
            }
            return null
        }

        /** 记录一次分配，并同步进 busy，避免后续任务与它重叠 */
        fun commit(taskId: String, title: String, date: LocalDate, span: Pair<Int, Int>, original: String?) {
            assignments += Assignment(taskId, title, date, span.first, span.second, original)
            busyByDate.getOrPut(date) { mutableListOf() } += Busy(date, span.first, span.second)
        }

        // ── 1) 整块任务：按「优先级 → 时长降序」贪心 ──
        // 长任务先排能减少碎片浪费；同优先级下先排长的比先排短的更容易成功。
        val orderedTasks = tasks.sortedWith(
            compareBy({ priorityWeight(it.priority) }, { -it.minutes }),
        )
        for (task in orderedTasks) {
            var placed = false
            for (dayOffset in 0 until MAX_DAYS) {
                val date = startDate.plusDays(dayOffset.toLong())
                val slot = findSlot(date, task.minutes) ?: continue
                commit(task.taskId, task.title, date, slot, task.originalReminder)
                placed = true
                break
            }
            if (!placed) {
                // 90 天内真的排不下：说明是时间窗容量问题，如实说明
                unplaced += Unplaced(
                    task.taskId, task.title,
                    "90 天内没有足够时间窗（需连续 ${task.minutes} 分钟）",
                )
            }
        }

        // ── 2) 可碎片化任务：每天在空档里插入若干块 ──
        for (frag in fragmented) {
            val chunk = if (frag.chunkMinutes > 0) frag.chunkMinutes else frag.minutesPerDay
            var remaining = frag.minutesPerDay
            var dayOffset = 0
            var placedAny = false
            while (remaining > 0 && dayOffset < MAX_DAYS) {
                val date = startDate.plusDays(dayOffset.toLong())
                val need = minOf(remaining, chunk)
                val slot = findSlot(date, need)
                if (slot != null) {
                    // 碎片任务每天只插一块，均匀摊到多天，而不是一天塞满
                    commit(frag.taskId ?: frag.title, frag.title, date, slot, null)
                    remaining -= need
                    placedAny = true
                }
                dayOffset++
            }
            if (!placedAny) {
                unplaced += Unplaced(frag.taskId, frag.title, "90 天内没有足够时间窗")
            } else if (remaining > 0) {
                unplaced += Unplaced(
                    frag.taskId, frag.title,
                    "90 天内只能排入 ${frag.minutesPerDay - remaining}/${frag.minutesPerDay} 分钟",
                )
            }
        }

        val sortedAssignments = assignments.sortedWith(compareBy({ it.date }, { it.start }))
        // 最早完成日期 = 最后一项分配的日期（贪心是按天推进的，最后一个即最晚）
        val earliest = sortedAssignments.maxOfOrNull { it.date }
        return Result(sortedAssignments, unplaced, earliest)
    }

    /**
     * 从数据库现状推导「已占用」区间。
     *
     * 规则（4.3）：已有 reminder 且有 expected_minutes 的任务，其 [reminder, reminder+时长)
     * 视为已占用；无 reminder 或无预计时长的任务不参与（不知道它占多久）。
     *
     * @param all          全部任务
     * @param excludeIds   本次要重新排期的任务 ID（它们的位置本来就要改，不应算作占用）
     * @param localDate    只关心排期起点之后的日期
     */
    fun busyFromTasks(
        all: List<TaskData>,
        excludeIds: Set<String>,
        localDate: LocalDate,
    ): List<Busy> {
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
        return all.mapNotNull { task ->
            if (task.id in excludeIds) return@mapNotNull null
            val minutes = task.expectedMinutes ?: return@mapNotNull null
            if (minutes <= 0) return@mapNotNull null
            val raw = task.reminder ?: return@mapNotNull null
            // reminder 可能只有日期（全天），这种无法确定起点，跳过
            val ldt = try {
                LocalDateTime.parse(raw, fmt)
            } catch (_: Exception) {
                return@mapNotNull null
            }
            val date = ldt.toLocalDate()
            if (date.isBefore(localDate)) return@mapNotNull null
            val startMin = ldt.hour * 60 + ldt.minute
            Busy(date, startMin, (startMin + minutes.toInt()).coerceAtMost(1440))
        }
    }

    /**
     * 把分配结果写回任务 reminder（confirm=true 时调用）。
     *
     * 一次 update_task_full 调用改一个任务的 reminder，逐项返回结果：
     * 部分失败必须如实报告哪几项没写成功，不能整体报成功。
     *
     * @param core        核心库
     * @param assignments 要应用的分项（已由调用方做过期校验过滤）
     * @return 已成功应用的任务 ID 集合与逐项错误
     */
    fun applyAssignments(
        core: NionCore,
        assignments: List<Assignment>,
    ): Pair<Set<String>, Map<String, String>> {
        val applied = mutableSetOf<String>()
        val errors = mutableMapOf<String, String>()
        for (a in assignments) {
            val reminderText = "${a.date.format(DateTimeFormatter.ISO_LOCAL_DATE)}T${a.startText}"
            try {
                core.updateTaskFull(
                    id = a.taskId,
                    name = null, description = null, priority = null, status = null,
                    kind = null, expectedMinutes = null, clearExpectedMinutes = false,
                    categoryId = null,
                    setGroup = false, groupId = null,
                    setParent = false, parentId = null,
                    reminder = reminderText, clearReminder = false,
                    recurrenceRule = null, recurrenceReminderTime = null,
                )
                applied += a.taskId
            } catch (e: Exception) {
                errors[a.taskId] = e.message ?: "写入失败"
            }
        }
        return applied to errors
    }
}
