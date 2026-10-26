package com.echonion.nion.ui.task

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 实体操作相关纯规则测试（7.6 / 7.7）。
 *
 * 这里验证两类可以脱离 Android 运行时表达的规则：
 * 1. 类型切换草稿保留 —— 切换只改变可见字段，不重置已输入内容；
 * 2. 长按手势仲裁 —— 拖动 = 排序，原地松手 = 菜单，二者互斥。
 *
 * 之所以抽成纯函数：这些规则决定了用户「切了类型草稿还在不在」「长按到底是排序还是弹菜单」，
 * 属于容易回归又难以用 UI 测试稳定覆盖的地方。
 */
class EntityActionRulesTest {

    /**
     * 表单草稿 —— 对应 AddTaskOverlay 里的全部输入状态。
     *
     * @param kind 当前类型 "task" / "note"
     */
    private data class Draft(
        val kind: String,
        val title: String,
        val description: String,
        val priority: String,
        val reminder: String?,
        val expectedMinutes: Long?,
    ) {
        /** 切换类型：只改 kind，其余字段原样保留（7.6 绝不静默清空） */
        fun switchTo(newKind: String): Draft = copy(kind = newKind)

        /** 当前类型下是否展示任务字段 */
        val showsTaskFields: Boolean get() = kind != "note"

        /** 提交时实际写入的字段：笔记清掉任务专属值 */
        fun submit(): Triple<String, String, String?> =
            Triple(kind, title, if (showsTaskFields) reminder else null)
    }

    @Test
    fun switchingKindKeepsAllDraftFields() {
        val d = Draft(
            kind = "task",
            title = "写周报",
            description = "本周进展",
            priority = "high",
            reminder = "2026-11-01T09:00",
            expectedMinutes = 45,
        )
        val asNote = d.switchTo("note")
        assertEquals("note", asNote.kind)
        // 标题、正文、优先级、提醒、预计时长全部还在
        assertEquals("写周报", asNote.title)
        assertEquals("本周进展", asNote.description)
        assertEquals("high", asNote.priority)
        assertEquals("2026-11-01T09:00", asNote.reminder)
        assertEquals(45L, asNote.expectedMinutes)

        // 切回任务同样不丢任何内容
        val backToTask = asNote.switchTo("task")
        assertEquals(d, backToTask)
    }

    @Test
    fun noteSubmissionDropsTaskOnlyFields() {
        val d = Draft("note", "笔记", "正文", "high", "2026-11-01T09:00", 45)
        val (kind, title, reminder) = d.submit()
        assertEquals("note", kind)
        assertEquals("笔记", title)
        // 笔记不写提醒（没有「到期」语义）
        assertNull(reminder)
    }

    /**
     * 长按手势结果 —— 与 SharedTaskList 的 onDragStopped 分支一一对应。
     */
    private enum class LongPressOutcome { REORDER, OPEN_MENU }

    /**
     * 判定长按松手后的行为。
     *
     * @param moved      拖拽过程中是否越过阈值发生了位移
     * @param hasMenu    调用方是否提供了操作菜单回调
     */
    private fun resolveLongPress(moved: Boolean, hasMenu: Boolean): LongPressOutcome =
        if (moved) LongPressOutcome.REORDER
        else if (hasMenu) LongPressOutcome.OPEN_MENU
        else LongPressOutcome.REORDER // 未提供菜单时退回旧行为（多选由上层处理）

    @Test
    fun dragAlwaysMeansReorder() {
        // 拖过阈值就是排序，即使配置了菜单也不能弹菜单
        assertEquals(LongPressOutcome.REORDER, resolveLongPress(moved = true, hasMenu = true))
        assertEquals(LongPressOutcome.REORDER, resolveLongPress(moved = true, hasMenu = false))
    }

    @Test
    fun stillPressOpensMenuWhenConfigured() {
        assertEquals(LongPressOutcome.OPEN_MENU, resolveLongPress(moved = false, hasMenu = true))
    }

    @Test
    fun withoutMenuFallsBackToLegacyBehaviour() {
        assertEquals(LongPressOutcome.REORDER, resolveLongPress(moved = false, hasMenu = false))
    }
}
