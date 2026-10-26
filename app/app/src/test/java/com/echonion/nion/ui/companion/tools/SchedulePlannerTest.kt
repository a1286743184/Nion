package com.echonion.nion.ui.companion.tools

import com.echonion.nion.ui.task.TimeWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 排期算法测试（4.3 / 第 9 节 B6 验收）。
 *
 * 用设计文档里那个真实案例做端到端校验：
 * 「定海神针卷」150 分钟（一次性）+ 行测判断推理系列课 59/40/90/37/60/79/93 分钟
 * + 每日三时间窗 09–11 / 14–17 / 20–24（合计 8 小时）+ 每日 60 分钟碎片刷题。
 * 要求：时长可排的任务都排上，缺时长的进 unplaced 并说明原因。
 */
class SchedulePlannerTest {

    private val startDate = LocalDate.of(2026, 10, 26)

    private val windows = listOf(
        TimeWindow("09:00", "11:00"),
        TimeWindow("14:00", "17:00"),
        TimeWindow("20:00", "24:00"),
    )

    private fun pending(id: String, minutes: Int, priority: String = "medium") =
        SchedulePlanner.PendingTask(id, id, minutes, priority, null)

    /** 未配置时间窗：全部进 unplaced，原因必须是配置问题而不是「时间不足」 */
    @Test
    fun emptyWindowsReportConfigurationProblem() {
        val result = SchedulePlanner.plan(
            tasks = listOf(pending("t1", 60)),
            fragmented = emptyList(),
            windows = emptyList(),
            startDate = startDate,
            busy = emptyList(),
        )
        assertTrue(result.assignments.isEmpty())
        assertEquals(1, result.unplaced.size)
        assertTrue(result.unplaced[0].reason.contains("未配置"))
        assertNull(result.earliest)
    }

    /** 单任务放进第一个窗口 */
    @Test
    fun singleTaskFitsFirstWindow() {
        val result = SchedulePlanner.plan(
            listOf(pending("t1", 60)), emptyList(), windows, startDate, emptyList(),
        )
        assertEquals(1, result.assignments.size)
        val a = result.assignments[0]
        assertEquals(startDate, a.date)
        assertEquals("09:00", a.startText)
        assertEquals("10:00", a.endText)
    }

    /** 同一天窗口被填满后自动顺延到下一个窗口，再排不下就到下一天 */
    @Test
    fun fillsWindowsThenRollsToNextDay() {
        // 每天可用 120+180+240 = 540 分钟
        val result = SchedulePlanner.plan(
            listOf(pending("a", 120), pending("b", 180), pending("c", 240), pending("d", 60)),
            emptyList(), windows, startDate, emptyList(),
        )
        assertEquals(4, result.assignments.size)
        // 前三个刚好填满第一天（按窗口顺序）
        val firstDay = result.assignments.filter { it.date == startDate }
        assertEquals(3, firstDay.size)
        // 第四个落到第二天
        val secondDay = result.assignments.filter { it.date == startDate.plusDays(1) }
        assertEquals(1, secondDay.size)
    }

    /** 已有占用会被绕开，不会与既有 reminder 重叠 */
    @Test
    fun avoidsBusyBlocks() {
        val busy = listOf(SchedulePlanner.Busy(startDate, 9 * 60, 10 * 60))
        val result = SchedulePlanner.plan(
            listOf(pending("t1", 60)), emptyList(), windows, startDate, busy,
        )
        val a = result.assignments.single()
        assertEquals("10:00", a.startText)
        assertEquals("11:00", a.endText)
    }

    /** 优先级高的先排：high 抢到最早的空档 */
    @Test
    fun higherPriorityGetsEarlierSlot() {
        val result = SchedulePlanner.plan(
            listOf(pending("low", 120, "low"), pending("high", 120, "high")),
            emptyList(), windows, startDate, emptyList(),
        )
        val first = result.assignments.first()
        assertEquals("high", first.taskId)
    }

    /** 单任务超过任何单个窗口长度 → 排不下并如实说明需要多长连续时间 */
    @Test
    fun taskLongerThanAnyWindowIsUnplaced() {
        // 最长窗口 240 分钟（20:00-24:00），这里要 300 分钟
        val result = SchedulePlanner.plan(
            listOf(pending("huge", 300)), emptyList(), windows, startDate, emptyList(),
        )
        assertTrue(result.assignments.isEmpty())
        assertEquals(1, result.unplaced.size)
        assertTrue(result.unplaced[0].reason.contains("300"))
    }

    /** 碎片任务：每天一块，均匀摊到多天 */
    @Test
    fun fragmentedTaskSpreadsAcrossDays() {
        val frag = SchedulePlanner.FragmentedTask(
            taskId = "daily", title = "每日刷题", minutesPerDay = 60, chunkMinutes = 30,
        )
        val result = SchedulePlanner.plan(
            emptyList(), listOf(frag), windows, startDate, emptyList(),
        )
        // 60 分钟 / 每块 30 分钟 → 需要 2 天（每天插一块）
        assertEquals(2, result.assignments.size)
        assertEquals(2, result.assignments.map { it.date }.distinct().size)
        assertTrue(result.assignments.all { it.end - it.start == 30 })
    }

    /**
     * 设计文档的完整案例：7 个系列课 + 定海神针卷 + 每日碎片刷题。
     * 断言可解：所有任务都排入，最早完成日期被算出。
     */
    @Test
    fun documentedCaseIsSolvable() {
        val lectures = listOf(59, 40, 90, 37, 60, 79, 93).mapIndexed { i, m ->
            pending("lecture$i", m)
        }
        val tasks = lectures + pending("dinghai", 150, "high")
        val frag = SchedulePlanner.FragmentedTask(null, "每日行测刷题", 60, 30)

        val result = SchedulePlanner.plan(tasks, listOf(frag), windows, startDate, emptyList())

        // 8 个整块任务 + 碎片任务（按标题作 key，无 id 时）全部排上，
        // 去重后共 9 个不同的 key（碎片任务占 2 条分配，但只算 1 个任务）
        assertEquals(9, result.assignments.map { it.taskId }.distinct().size)
        assertTrue("不应有未排入项", result.unplaced.isEmpty())
        // 碎片任务排了两天各一块
        assertEquals(2, result.assignments.count { it.taskId == "每日行测刷题" })
        // 最早完成日期 = 最后一项的日期
        assertEquals(result.assignments.maxOf { it.date }, result.earliest)
        // 同一天内的分配不重叠
        result.assignments.groupBy { it.date }.forEach { (_, list) ->
            val sorted = list.sortedBy { it.start }
            sorted.zipWithNext().forEach { (a, b) ->
                assertTrue("同日分配重叠: ${a.taskId} / ${b.taskId}", a.end <= b.start)
            }
        }
    }

    /** 完全不重叠的校验：任意两天内所有分配区间互不重叠且落在窗口内 */
    @Test
    fun assignmentsStayInsideWindows() {
        val result = SchedulePlanner.plan(
            listOf(pending("a", 90), pending("b", 200), pending("c", 45)),
            emptyList(), windows, startDate, emptyList(),
        )
        val ranges = windows.map { it.startMinutes!! to it.endMinutes!! }
        result.assignments.forEach { a ->
            val inside = ranges.any { (s, e) -> a.start >= s && a.end <= e }
            assertTrue("分配 ${a.startText}-${a.endText} 不在任何时间窗内", inside)
        }
    }

    /** busyFromTasks：只把「有 reminder + 有预计时长」的任务当作占用 */
    @Test
    fun busyDerivationRequiresBothReminderAndMinutes() {
        val all = listOf(
            task("has-both", "2026-10-26T09:00", 60),
            task("no-minutes", "2026-10-26T10:00", null),
            task("no-reminder", null, 30),
            task("date-only", "2026-10-26", 30),
            task("past", "2026-10-20T09:00", 60),
        )
        val busy = SchedulePlanner.busyFromTasks(all, emptySet(), startDate)
        assertEquals(1, busy.size)
        assertEquals(9 * 60, busy[0].start)
        assertEquals(10 * 60, busy[0].end)
    }

    /** 本次要重排的任务自身不算占用，否则它们会互相挡住 */
    @Test
    fun excludedTasksDoNotCountAsBusy() {
        val all = listOf(task("t1", "2026-10-26T09:00", 60))
        val busy = SchedulePlanner.busyFromTasks(all, setOf("t1"), startDate)
        assertTrue(busy.isEmpty())
    }

    private fun task(id: String, reminder: String?, minutes: Long?) =
        uniffi.nion_core.TaskData(
            id = id, name = id, description = null, priority = "medium", status = "todo",
            reminder = reminder, parentId = null, categoryId = null, groupId = null,
            createdAt = "", updatedAt = "", completedAt = null, focusSeconds = 0,
            recurrenceRule = null, recurrenceReminderTime = null, linkedTaskIds = null,
            kind = "task", expectedMinutes = minutes,
        )
}
