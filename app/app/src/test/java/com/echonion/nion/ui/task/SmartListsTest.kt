package com.echonion.nion.ui.task

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 智能清单聚合口径测试（第 5 节定稿 + 7.12）。
 *
 * 重点验证的是容易出错、且跨时区/周边界会翻车的部分：
 * - 「本周」是本地时区周一至周日，含今天；
 * - 「最近 N 天」是含今天的连续 N 个自然日向后看，不是未来 N 天；
 * - 无 reminder 的实体不进任何时间视图；
 * - 今天/收集箱锁定启用，设置被改坏也不会出现空侧栏。
 */
class SmartListsTest {

    private fun task(
        id: String,
        reminder: String? = null,
        categoryId: String? = "cl1",
        kind: String = "task",
    ) = TaskItem(
        id = id,
        name = id,
        description = null,
        priority = "medium",
        isDone = false,
        createdAt = "",
        reminder = reminder,
        kind = kind,
        categoryId = categoryId,
    )

    /** 2026-10-26 是周一，用来精确验证周围界 */
    private val monday = LocalDate.of(2026, 10, 26)

    @Test
    fun todayMatchesOnlyReminderDateEqualsToday() {
        val all = listOf(
            task("a", "2026-10-26"),
            task("b", "2026-10-26T09:00"),
            task("c", "2026-10-25"),
            task("d", "2026-10-27"),
            task("e", null),
        )
        val result = SmartLists.aggregate(SmartLists.TODAY, monday, all).map { it.id }
        assertEquals(listOf("a", "b"), result)
    }

    @Test
    fun tomorrowMatchesOnlyNextDay() {
        val all = listOf(task("a", "2026-10-26"), task("b", "2026-10-27T08:30"))
        val result = SmartLists.aggregate(SmartLists.TOMORROW, monday, all).map { it.id }
        assertEquals(listOf("b"), result)
    }

    /** 周一当天：本周 = 10-26 至 11-01（周一至周日，含今天） */
    @Test
    fun weekSpansMondayToSundayInclusive() {
        val all = listOf(
            task("before", "2026-10-25"),      // 上周日，不在本周
            task("monday", "2026-10-26"),
            task("sunday", "2026-11-01"),
            task("nextMonday", "2026-11-02"),  // 下周一，不在本周
        )
        val result = SmartLists.aggregate(SmartLists.WEEK, monday, all).map { it.id }
        assertEquals(listOf("monday", "sunday"), result)
    }

    /** 周日当天：本周仍是同一周的周一至周日，不能因为跨月/跨周滚到下一周 */
    @Test
    fun weekFromSundayStillCoversSameWeek() {
        val sunday = LocalDate.of(2026, 11, 1)
        val all = listOf(
            task("monday", "2026-10-26"),
            task("today", "2026-11-01"),
            task("nextMonday", "2026-11-02"),
        )
        val result = SmartLists.aggregate(SmartLists.WEEK, sunday, all).map { it.id }
        assertEquals(listOf("monday", "today"), result)
    }

    /** 最近 7 天：含今天，向前 6 天；未来日期不入选 */
    @Test
    fun last7DaysLooksBackwardIncludingToday() {
        val today = LocalDate.of(2026, 10, 26)
        val all = listOf(
            task("d-6", "2026-10-20"),   // 边界内
            task("d-7", "2026-10-19"),   // 超出（第 8 天）
            task("today", "2026-10-26"),
            task("future", "2026-10-27"), // 未来，不属于「最近」
        )
        val result = SmartLists.aggregate(SmartLists.LAST_7D, today, all).map { it.id }
        assertEquals(listOf("d-6", "today"), result)
    }

    /** 最近 30 天：含今天，向前 29 天 */
    @Test
    fun last30DaysBoundary() {
        val today = LocalDate.of(2026, 10, 26)
        val all = listOf(
            task("d-29", "2026-09-27"),  // 边界内（含今天第 30 天）
            task("d-30", "2026-09-26"),  // 超出
            task("today", "2026-10-26"),
        )
        val result = SmartLists.aggregate(SmartLists.LAST_30D, today, all).map { it.id }
        assertEquals(listOf("d-29", "today"), result)
    }

    /** 收集箱：category_id 为空即入选，与 reminder 无关 */
    @Test
    fun inboxIgnoresReminderAndOnlyChecksCategory() {
        val all = listOf(
            task("orphanWithDate", "2026-10-26", categoryId = null),
            task("orphanNoDate", null, categoryId = null),
            task("filed", "2026-10-26", categoryId = "cl1"),
        )
        val result = SmartLists.aggregate(SmartLists.INBOX, monday, all).map { it.id }
        assertEquals(listOf("orphanWithDate", "orphanNoDate"), result)
    }

    /** 无 reminder 的实体不进任何时间视图 */
    @Test
    fun noReminderNeverEntersTimeViews() {
        val all = listOf(task("noDate", null, categoryId = "cl1"))
        listOf(SmartLists.TODAY, SmartLists.TOMORROW, SmartLists.WEEK, SmartLists.LAST_7D, SmartLists.LAST_30D)
            .forEach { id ->
                assertTrue("$id 不应包含无提醒实体", SmartLists.aggregate(id, monday, all).isEmpty())
            }
    }

    /** 笔记同样按 reminder 参与时间视图（7.4：今天有 N 条笔记也要能显示） */
    @Test
    fun notesParticipateInTimeViews() {
        val all = listOf(task("note1", "2026-10-26", kind = "note"))
        val result = SmartLists.aggregate(SmartLists.TODAY, monday, all).map { it.id }
        assertEquals(listOf("note1"), result)
    }

    /** 非法 reminder 字符串不应导致崩溃，也不入选 */
    @Test
    fun malformedReminderIsIgnored() {
        val all = listOf(task("bad", "not-a-date"))
        assertTrue(SmartLists.aggregate(SmartLists.TODAY, monday, all).isEmpty())
    }

    /** 锁定项强制启用：设置里只保存了空集合，实际启用集合仍含今天与收集箱 */
    @Test
    fun lockedListsAreAlwaysEnabled() {
        val effective = SmartLists.effectiveEnabledIds(emptySet())
        assertTrue(SmartLists.TODAY in effective)
        assertTrue(SmartLists.INBOX in effective)
        // 非锁定项不应被强行加回来
        assertTrue(SmartLists.WEEK !in effective)
    }

    /** 默认启用集合就是今天 + 收集箱，保证升级前默认视图不变 */
    @Test
    fun defaultsAreTodayAndInboxOnly() {
        assertEquals(setOf(SmartLists.TODAY, SmartLists.INBOX), SmartLists.defaultEnabledIds())
    }

    /** visible 保持注册表顺序，与保存顺序无关 */
    @Test
    fun visibleKeepsRegistryOrder() {
        val visible = SmartLists.visible(setOf(SmartLists.LAST_7D, SmartLists.TODAY, SmartLists.WEEK))
        assertEquals(
            listOf(SmartLists.TODAY, SmartLists.WEEK, SmartLists.LAST_7D),
            visible.map { it.id },
        )
    }

    /** 未知 ID 聚合返回空列表，不抛异常 */
    @Test
    fun unknownIdAggregatesToEmpty() {
        assertTrue(SmartLists.aggregate("no_such_list", monday, listOf(task("a", "2026-10-26"))).isEmpty())
    }
}
