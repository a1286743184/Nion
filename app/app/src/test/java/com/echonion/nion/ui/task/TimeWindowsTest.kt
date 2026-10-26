package com.echonion.nion.ui.task

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 每日时间窗校验测试（7.11）。
 *
 * 覆盖定稿的全部边界：24:00 只能作结束、起止关系、重叠、跨午夜、空数组合法、
 * 以及「新增默认时段」的连续排布规则。
 */
class TimeWindowsTest {

    @Test
    fun emptyListIsValidAndMeansUnconfigured() {
        assertTrue(TimeWindows.validate(emptyList()).isValid)
        // 空配置必须明确告诉用户去补，不能表现为「全天可用」
        assertEquals("未设置，AI 排期前需要补充", TimeWindows.summary(emptyList()))
    }

    @Test
    fun normalWindowsAreValid() {
        val windows = listOf(
            TimeWindow("09:00", "11:00"),
            TimeWindow("14:00", "17:00"),
            TimeWindow("20:00", "24:00"),
        )
        assertTrue(TimeWindows.validate(windows).isValid)
    }

    @Test
    fun endMustBeAfterStart() {
        val r = TimeWindows.validate(listOf(TimeWindow("11:00", "09:00")))
        assertFalse(r.isValid)
        assertEquals("结束时间需晚于开始时间", r.errorMessage)
        assertEquals(0, r.errorIndex)
    }

    @Test
    fun equalStartAndEndIsRejected() {
        val r = TimeWindows.validate(listOf(TimeWindow("09:00", "09:00")))
        assertFalse(r.isValid)
    }

    /** 24:00 只能作为结束值 */
    @Test
    fun midnight24OnlyAllowedAsEnd() {
        val asStart = TimeWindows.validate(listOf(TimeWindow("24:00", "24:00")))
        assertFalse(asStart.isValid)
        assertEquals("24:00 只能作为结束时间", asStart.errorMessage)

        val asEnd = TimeWindows.validate(listOf(TimeWindow("22:00", "24:00")))
        assertTrue(asEnd.isValid)
    }

    /** V1 不允许跨午夜：22:00–02:00 必须被拒绝 */
    @Test
    fun crossMidnightIsRejected() {
        val r = TimeWindows.validate(listOf(TimeWindow("22:00", "02:00")))
        assertFalse(r.isValid)
        assertEquals("结束时间需晚于开始时间", r.errorMessage)
    }

    /** 重叠检测并定位到后一行 */
    @Test
    fun overlappingWindowsReportSecondRow() {
        val windows = listOf(
            TimeWindow("09:00", "11:00"),
            TimeWindow("10:00", "12:00"),
        )
        val r = TimeWindows.validate(windows)
        assertFalse(r.isValid)
        assertEquals(1, r.errorIndex)
        assertTrue(r.errorMessage!!.contains("09:00–11:00"))
    }

    /** 相邻但不重叠是合法的（11:00 结束、11:00 开始） */
    @Test
    fun touchingWindowsAreNotOverlap() {
        val windows = listOf(
            TimeWindow("09:00", "11:00"),
            TimeWindow("11:00", "13:00"),
        )
        assertTrue(TimeWindows.validate(windows).isValid)
    }

    /** 乱序输入也要能检测出重叠（校验内部先按开始时间排序） */
    @Test
    fun outOfOrderInputStillDetected() {
        val windows = listOf(
            TimeWindow("14:00", "17:00"),
            TimeWindow("09:00", "10:00"),
            TimeWindow("15:00", "16:00"),
        )
        val r = TimeWindows.validate(windows)
        assertFalse(r.isValid)
        // 出错行是原始索引 2（15:00–16:00），与 14:00–17:00 重叠
        assertEquals(2, r.errorIndex)
    }

    @Test
    fun sortedOrdersByStartTime() {
        val sorted = TimeWindows.sorted(
            listOf(TimeWindow("20:00", "22:00"), TimeWindow("09:00", "11:00"))
        )
        assertEquals(listOf("09:00", "20:00"), sorted.map { it.start })
    }

    @Test
    fun jsonRoundTrip() {
        val windows = listOf(
            TimeWindow("09:00", "11:00"),
            TimeWindow("20:00", "24:00"),
        )
        val json = TimeWindows.toJson(windows)
        assertEquals(windows, TimeWindows.fromJson(json))
    }

    @Test
    fun corruptJsonDegradesToEmptyInsteadOfThrowing() {
        assertEquals(emptyList<TimeWindow>(), TimeWindows.fromJson("{not json"))
        assertEquals(emptyList<TimeWindow>(), TimeWindows.fromJson(null))
        assertEquals(emptyList<TimeWindow>(), TimeWindows.fromJson(""))
    }

    /** 新增时段默认值：无时段时 09:00–10:00，有时段时接在最后一段之后 1 小时 */
    @Test
    fun nextDefaultFollowsLastWindow() {
        assertEquals(TimeWindow("09:00", "10:00"), TimeWindows.nextDefault(emptyList()))
        assertEquals(
            TimeWindow("12:00", "13:00"),
            TimeWindows.nextDefault(listOf(TimeWindow("09:00", "11:00"))),
        )
        // 已排到 23:30 时剩余不足 1 小时：回退到 23:30–24:00，仍合法且不与上一段重叠
        val late = TimeWindows.nextDefault(listOf(TimeWindow("20:00", "23:30")))
        assertEquals("23:30", late.start)
        assertEquals("24:00", late.end)
        assertTrue(TimeWindows.validate(listOf(TimeWindow("20:00", "23:30"), late)).isValid)
    }

    @Test
    fun durationComputation() {
        assertEquals(120, TimeWindow("09:00", "11:00").durationMinutes)
        assertEquals(240, TimeWindow("20:00", "24:00").durationMinutes)
        // 非法时段时长为 0，调用方据此跳过
        assertEquals(0, TimeWindow("11:00", "09:00").durationMinutes)
    }

    @Test
    fun parseRejectsOutOfRange() {
        assertNull(TimeWindow.parseMinutes("24:00"))
        assertNull(TimeWindow.parseMinutes("09:60"))
        assertNull(TimeWindow.parseMinutes("9:00"))  // 两位小时是必须的
        assertNull(TimeWindow.parseMinutes("abc"))
        assertEquals(540, TimeWindow.parseMinutes("09:00"))
    }
}
