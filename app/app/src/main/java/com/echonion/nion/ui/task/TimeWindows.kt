package com.echonion.nion.ui.task

/**
 * 每日时间窗 —— AI 排期可占用的时段（V1 每天相同，7.11）。
 *
 * 校验规则（定稿）：
 * - 开始必须早于结束；
 * - 24:00 只能作为结束值（表示当天结束），不能作为开始；
 * - 普通时间范围 00:00～23:59；
 * - V1 不允许跨午夜区间（22:00–02:00），需用户拆成当天与次日两段；
 * - 任意两段不重叠；保存时按开始时间排序；
 * - 空数组合法，表示「未配置」而不是「全天可用」——排期工具遇到空数组必须
 *   把任务放进 unplaced 并说明原因，不能默认 00:00–24:00。
 *
 * @param start 开始时间 "HH:MM"
 * @param end   结束时间 "HH:MM"，允许 "24:00"
 */
data class TimeWindow(val start: String, val end: String) {

    /** 开始时间的分钟数（解析失败返回 null） */
    val startMinutes: Int? get() = parseMinutes(start)

    /**
     * 结束时间的分钟数。
     *
     * "24:00" 特判为 1440（当天结束），常规时间返回 0..1439。
     */
    val endMinutes: Int? get() = if (end == "24:00") 1440 else parseMinutes(end)

    /** 该时段长度（分钟）；非法时段返回 0 */
    val durationMinutes: Int
        get() {
            val s = startMinutes ?: return 0
            val e = endMinutes ?: return 0
            return (e - s).coerceAtLeast(0)
        }

    /** 展示用文案，如 "09:00–11:00" */
    val label: String get() = "$start–$end"

    companion object {
        /**
         * 解析 "HH:MM" 为分钟数；越界或格式错返回 null。
         *
         * 严格要求两位小时与两位分钟（"9:00" 视为非法）：这是 settings 与工具输出
         * 的既定格式，宽松解析会让 "9:0" 这类脏值悄悄通过校验，之后排期算错时间。
         */
        fun parseMinutes(text: String): Int? {
            val match = HOUR_MINUTE.matchEntire(text) ?: return null
            val h = match.groupValues[1].toIntOrNull() ?: return null
            val m = match.groupValues[2].toIntOrNull() ?: return null
            if (h !in 0..23 || m !in 0..59) return null
            return h * 60 + m
        }

        /** "HH:MM" 严格格式 */
        private val HOUR_MINUTE = Regex("""(\d{2}):(\d{2})""")

        /** 分钟数格式化为 "HH:MM"（1440 输出 "24:00"） */
        fun formatMinutes(total: Int): String {
            if (total >= 1440) return "24:00"
            return "%02d:%02d".format(total / 60, total % 60)
        }
    }
}

/**
 * 时间窗校验结果。
 *
 * @param errorMessage 行级错误信息，null 表示通过
 * @param errorIndex  出错的行索引（用于定位到对应行），null 表示整体错误或无错误
 */
data class TimeWindowValidation(
    val errorMessage: String? = null,
    val errorIndex: Int? = null,
) {
    val isValid: Boolean get() = errorMessage == null
}

/**
 * 每日时间窗的序列化与校验工具。
 *
 * 放在 UI 层而不是 core：这些是「设置表单」的语义，不涉及数据表结构；
 * core 只把 daily_time_windows 当作一个字符串键值存起来。
 */
object TimeWindows {

    /** settings 键：每日时间窗 JSON */
    const val SETTING_KEY = "daily_time_windows"

    /**
     * 校验整组时间窗（保存前调用）。
     *
     * @param windows 当前草稿
     * @return 首个错误；全部通过时返回 [TimeWindowValidation]（isValid=true）
     */
    fun validate(windows: List<TimeWindow>): TimeWindowValidation {
        // 单段自校验：格式、起止关系、24:00 只能作为结束
        windows.forEachIndexed { index, w ->
            if (w.start == "24:00") {
                return TimeWindowValidation("24:00 只能作为结束时间", index)
            }
            val s = w.startMinutes
            val e = w.endMinutes
            if (s == null) return TimeWindowValidation("开始时间格式不正确", index)
            if (e == null) return TimeWindowValidation("结束时间格式不正确", index)
            if (e <= s) return TimeWindowValidation("结束时间需晚于开始时间", index)
        }
        // 重叠检测：按开始时间排序后，相邻两段比较即可（O(n log n)）
        val sorted = windows.withIndex().sortedBy { it.value.startMinutes ?: 0 }
        for (i in 1 until sorted.size) {
            val prev = sorted[i - 1].value
            val cur = sorted[i].value
            val prevEnd = prev.endMinutes ?: continue
            val curStart = cur.startMinutes ?: continue
            if (curStart < prevEnd) {
                return TimeWindowValidation(
                    "此时段与 ${prev.label} 重叠",
                    sorted[i].index,
                )
            }
        }
        return TimeWindowValidation()
    }

    /**
     * 按开始时间排序（保存时使用；不影响用户编辑中的视觉顺序之外的内容）。
     */
    fun sorted(windows: List<TimeWindow>): List<TimeWindow> =
        windows.sortedBy { it.startMinutes ?: Int.MAX_VALUE }

    /**
     * 序列化为 settings 存值格式。
     *
     * 空列表存 "[]"：明确表达「用户配置过，但目前没有时段」，
     * 与「从未配置过」(键不存在) 区分开，排期工具可给出不同提示。
     */
    fun toJson(windows: List<TimeWindow>): String {
        // 手写 JSON 而不是用 org.json：结构极简（两个字符串字段的数组），
        // 手写既让 JVM 单元测试无需 Robolectric，也不会因为 JSON 库行为差异而变。
        // 字符串内容固定是 "HH:MM"，不含需要转义的字符。
        return sorted(windows).joinToString(prefix = "[", postfix = "]") { w ->
            "{\"start\":\"${w.start}\",\"end\":\"${w.end}\"}"
        }
    }

    /**
     * 从 settings 字符串解析时间窗。
     *
     * 任何解析异常都返回空列表（= 未配置），不抛出 —— 设置被改坏时
     * 排期应表现为「没有可用时间窗」，而不是让整个设置页崩掉。
     */
    fun fromJson(raw: String?): List<TimeWindow> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            // 每个对象形如 {"start":"09:00","end":"11:00"}，用正则逐段提取。
            // 解析失败（被外部改坏、旧格式）时返回空列表，由调用方按「未配置」处理。
            val objectRegex = Regex("""\{[^{}]*\}""")
            objectRegex.findAll(raw).mapNotNull { match ->
                val text = match.value
                val s = Regex(""""start"\s*:\s*"([^"]*)"""").find(text)?.groupValues?.get(1).orEmpty()
                val e = Regex(""""end"\s*:\s*"([^"]*)"""").find(text)?.groupValues?.get(1).orEmpty()
                if (s.isNotEmpty() && e.isNotEmpty()) TimeWindow(s, e) else null
            }.toList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * 折叠态副标题文案（7.11）。
     *
     * 无配置时必须写明「未设置，AI 排期前需要补充」，
     * 不能只显示一个空白的「未设置」让用户以为已够用。
     */
    fun summary(windows: List<TimeWindow>): String {
        if (windows.isEmpty()) return "未设置，AI 排期前需要补充"
        return windows.joinToString("、") { it.label } + " · 每天"
    }

    /**
     * 默认新增时段：取最后一个时段结束后 1 小时；无时段时 09:00–10:00。
     *
     * 这样连点「添加时段」会得到 09-10、11-12… 的连续可用区间，
     * 而不是每次都要手调时间。
     */
    fun nextDefault(windows: List<TimeWindow>): TimeWindow {
        if (windows.isEmpty()) return TimeWindow("09:00", "10:00")
        val lastEnd = windows.maxOfOrNull { it.endMinutes ?: 0 } ?: 0
        // 排在最后一段结束后的 1 小时。若剩余空间不足 1 小时（例如最后一段到 23:30），
        // 起点回退到该段结束处、终点固定在 24:00，保证：
        // - start < end（至少留 30 分钟，不能产生零长时段）；
        // - start 落在所有已有区间之后，天然不与任何时段重叠；
        // - start 不可能是 24:00（结束值专用）。
        val startMin = minOf(lastEnd + 60, 1410)
        val endMin = minOf(startMin + 60, 1440)
        return TimeWindow(TimeWindow.formatMinutes(startMin), TimeWindow.formatMinutes(endMin))
    }
}
