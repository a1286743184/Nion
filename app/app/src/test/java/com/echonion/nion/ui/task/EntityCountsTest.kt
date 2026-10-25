package com.echonion.nion.ui.task

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 实体统计口径测试（7.4 定稿表格）。
 *
 * 这里用一个与 TaskViewModel 内 EntityCounts 计算逻辑一致的小型纯函数做验证：
 * 统计必须遍历全部子孙实体、笔记按 kind 计数、已完成只含 task。
 * 之所以不复用 ViewModel：ViewModel 依赖 Application / NionCore，
 * 在 JVM 单测里无法构造；口径本身是可独立表达的业务规则，抽出来测更稳定。
 */
class EntityCountsTest {

    /** 与 TaskViewModel.entityCounts 相同的遍历规则 */
    private data class Counts(val todo: Int, val notes: Int, val done: Int) {
        val active: Int get() = todo + notes
    }

    private fun count(items: List<TaskItem>): Counts {
        var todo = 0
        var notes = 0
        var done = 0
        fun walk(list: List<TaskItem>) {
            for (item in list) {
                if (item.isNote) notes++
                else if (item.isDone || item.isCompletedForDate) done++
                else todo++
                if (item.subtasks.isNotEmpty()) walk(item.subtasks)
            }
        }
        walk(items)
        return Counts(todo, notes, done)
    }

    /** 构造最小 TaskItem，只填统计关心的字段 */
    private fun item(
        id: String,
        kind: String = "task",
        isDone: Boolean = false,
        subtasks: List<TaskItem> = emptyList(),
    ) = TaskItem(
        id = id,
        name = id,
        description = null,
        priority = "medium",
        isDone = isDone,
        createdAt = "",
        subtasks = subtasks,
        kind = kind,
    )

    /** 纯任务：待办与已完成分开计数，笔记为 0 */
    @Test
    fun pureTasks() {
        val c = count(listOf(item("a"), item("b", isDone = true)))
        assertEquals(1, c.todo)
        assertEquals(1, c.done)
        assertEquals(0, c.notes)
    }

    /** 纯笔记：全部算笔记，已完成始终为 0（即使 status 是 done） */
    @Test
    fun pureNotes() {
        val c = count(listOf(item("n1", kind = "note", isDone = true), item("n2", kind = "note")))
        assertEquals(2, c.notes)
        assertEquals(0, c.done)
        assertEquals(0, c.todo)
    }

    /** 混排：笔记与任务各自计数，进行中 = 未完成任务 + 全部笔记 */
    @Test
    fun mixedCountsNotesAndTasksSeparately() {
        val c = count(
            listOf(
                item("t1"),
                item("n1", kind = "note"),
                item("t2", isDone = true),
            )
        )
        assertEquals(1, c.todo)
        assertEquals(1, c.notes)
        assertEquals(1, c.done)
        assertEquals(2, c.active)
    }

    /** 子孙实体也计入统计（父项折叠不应改变数字） */
    @Test
    fun includesDescendants() {
        val c = count(
            listOf(
                item(
                    "parent",
                    subtasks = listOf(
                        item("child1", kind = "note"),
                        item("child2", subtasks = listOf(item("grandchild", isDone = true))),
                    ),
                )
            )
        )
        // parent 与 child2 都是未完成 task，child1 是笔记，grandchild 是已完成 task
        assertEquals(2, c.todo)
        assertEquals(1, c.notes)
        assertEquals(1, c.done)
    }

    /** isNote 只认 kind=note，不认其它值 */
    @Test
    fun kindIsOnlyNoteOrTask() {
        assertTrue(item("x", kind = "note").isNote)
        assertFalse(item("y", kind = "task").isNote)
        assertFalse(item("z").isNote)
    }

    /** 笔记不参与完成：即使 isDone=true 也不计入已完成 */
    @Test
    fun noteNeverCountsAsDone() {
        val c = count(listOf(item("n", kind = "note", isDone = true)))
        assertEquals(0, c.done)
        assertEquals(1, c.notes)
    }
}
