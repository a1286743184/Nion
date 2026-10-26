package com.echonion.nion.ui.task

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

/**
 * 智能清单定义 —— 用注册表替代散落在 ViewModel 里的 if (id == TODAY_ID) 特判。
 *
 * 为什么改成注册表：
 * - 旧实现只有「今天」「收集箱」两个硬编码分支，每加一个聚合视图就要再改一遍
 *   内层查询、侧栏渲染、计数、空态文案四处代码；
 * - 现在每个清单只声明「怎么算入选」与「怎么排序」，其余（侧栏、设置、计数）统一遍历。
 *
 * @param id      稳定 ID，与 Sidebar / 设置里保存的启用集合一致
 * @param label   侧栏显示名
 * @param settingDescription 设置页里的一行口径说明
 * @param defaultEnabled 默认是否启用
 * @param locked  是否锁定启用（今天、收集箱锁定，防止出现空侧栏）
 * @param aggregate 聚合函数：给定「今天」和全部顶层实体，返回该清单应包含的实体
 */
data class SmartListDef(
    val id: String,
    val label: String,
    val settingDescription: String,
    val defaultEnabled: Boolean,
    val locked: Boolean,
    val aggregate: (today: LocalDate, all: List<TaskItem>) -> List<TaskItem>,
)

/**
 * 智能清单注册表 —— 内置全部六项，顺序即侧栏固定顺序（7.12）。
 *
 * 聚合口径（第 5 节定稿，全部按 reminder 的**本地日历日期**）：
 * - 今天 / 明天：reminder 日期等于目标日；含已完成实例（沿用列表的待办/已完成分区）；
 * - 本周：本地时区的周一至周日，含今天；
 * - 最近 7 / 30 天：含今天在内向前连续 7 / 30 个自然日，不是「未来 N 天」；
 * - 收集箱：category_id 为空（未归入任何清单），与既有 INBOX 口径一致；
 * - 无 reminder 的实体不进任何时间视图，只留在其真实清单或收集箱；
 * - expected_minutes 不参与入选判断，只影响时间占用。
 */
object SmartLists {

    /** 今天 */
    const val TODAY = "today"
    /** 明天 */
    const val TOMORROW = "smart_tomorrow"
    /** 本周（周一至周日） */
    const val WEEK = "smart_week"
    /** 最近 7 天（含今天向前） */
    const val LAST_7D = "smart_7d"
    /** 最近 30 天（含今天向前） */
    const val LAST_30D = "smart_30d"
    /** 收集箱 */
    const val INBOX = "inbox"

    /** 日期解析器：reminder 前 10 位是 "YYYY-MM-DD" */
    private val DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE

    /**
     * 取实体的提醒日期（本地日历日）。
     *
     * reminder 可能是 "YYYY-MM-DD" 或 "YYYY-MM-DDTHH:MM" 两种形式，
     * 统一截前 10 位解析；解析失败或无 reminder 返回 null（不参与时间视图）。
     */
    fun reminderDateOf(item: TaskItem): LocalDate? {
        val raw = item.reminder ?: return null
        return try {
            LocalDate.parse(raw.take(10), DATE_FMT)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 全部内置智能清单，顺序固定（侧栏顺序 = 这里的顺序）。
     *
     * 注意：聚合函数只处理「顶层实体」，子项随父项递归加载，避免父子被拆到两个分区。
     */
    val all: List<SmartListDef> = listOf(
        SmartListDef(
            id = TODAY,
            label = "今天",
            settingDescription = "提醒日期是今天",
            defaultEnabled = true,
            locked = true,
            aggregate = { today, all -> all.filter { reminderDateOf(it) == today } },
        ),
        SmartListDef(
            id = TOMORROW,
            label = "明天",
            settingDescription = "提醒日期是明天",
            defaultEnabled = false,
            locked = false,
            aggregate = { today, all -> all.filter { reminderDateOf(it) == today.plusDays(1) } },
        ),
        SmartListDef(
            id = WEEK,
            label = "本周",
            settingDescription = "本周一至周日，含今天",
            defaultEnabled = false,
            locked = false,
            aggregate = { today, all ->
                val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                val sunday = monday.plusDays(6)
                all.filter { d ->
                    val rd = reminderDateOf(d)
                    rd != null && !rd.isBefore(monday) && !rd.isAfter(sunday)
                }
            },
        ),
        SmartListDef(
            id = LAST_7D,
            label = "最近 7 天",
            settingDescription = "今天及此前 6 天",
            defaultEnabled = false,
            locked = false,
            aggregate = { today, all ->
                val start = today.minusDays(6)
                all.filter { d ->
                    val rd = reminderDateOf(d)
                    rd != null && !rd.isBefore(start) && !rd.isAfter(today)
                }
            },
        ),
        SmartListDef(
            id = LAST_30D,
            label = "最近 30 天",
            settingDescription = "今天及此前 29 天",
            defaultEnabled = false,
            locked = false,
            aggregate = { today, all ->
                val start = today.minusDays(29)
                all.filter { d ->
                    val rd = reminderDateOf(d)
                    rd != null && !rd.isBefore(start) && !rd.isAfter(today)
                }
            },
        ),
        SmartListDef(
            id = INBOX,
            label = "收集箱",
            settingDescription = "尚未归入清单的内容",
            defaultEnabled = true,
            locked = true,
            // 收集箱按「未归入清单」判定，与时间无关；子项随父项加载
            aggregate = { _, all -> all.filter { it.categoryId == null } },
        ),
    )

    /** 按 ID 找定义；不存在返回 null（真实清单也走这里判断） */
    fun find(id: String?): SmartListDef? = all.find { it.id == id }

    /** 是否智能清单 ID（用于区分真实清单） */
    fun isSmart(id: String?): Boolean = find(id) != null

    /** 默认启用集合：今天 + 收集箱，保证升级前默认视图不变 */
    fun defaultEnabledIds(): Set<String> = all.filter { it.defaultEnabled }.map { it.id }.toSet()

    /**
     * 计算「实际可用」的启用集合。
     *
     * 锁定项（今天、收集箱）无论设置里写了什么都会被强制启用：
     * 设置文件被改坏或旧版本留下空集合时，用户也不会得到一个空侧栏。
     *
     * @param stored 设置里保存的启用 ID 集合
     */
    fun effectiveEnabledIds(stored: Set<String>): Set<String> {
        val locked = all.filter { it.locked }.map { it.id }
        return stored + locked
    }

    /**
     * 按启用集合返回侧栏要显示的智能清单（保持注册表顺序）。
     *
     * @param enabled 已启用 ID 集合（调用方应先经 [effectiveEnabledIds] 归一）
     */
    fun visible(enabled: Set<String>): List<SmartListDef> = all.filter { it.id in enabled }

    /**
     * 聚合结果 —— 供 ViewModel 一次性算出多个智能清单的数据。
     *
     * @param id    智能清单 ID
     * @param today 今天（本地日期）
     * @param all   全部顶层实体
     * @return 该清单包含的实体；ID 未知时返回空列表
     */
    fun aggregate(id: String, today: LocalDate, all: List<TaskItem>): List<TaskItem> =
        find(id)?.aggregate?.invoke(today, all) ?: emptyList()
}
