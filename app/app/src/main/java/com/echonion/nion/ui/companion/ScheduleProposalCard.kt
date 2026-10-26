package com.echonion.nion.ui.companion

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.echonion.nion.ui.icons.LucideIcons
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 排期建议卡片的结构化模型（4.3.2 / 7.10）。
 *
 * 为什么不用模型输出的 Markdown 表格：写回必须依赖稳定任务 id 与「预览时的原值」，
 * 标题可能重复、模型可能改写，一旦用它当写回依据就会出现改错任务或覆盖用户手动修改。
 * 这里的数据全部由 schedule 工具的结构化结果映射而来，随消息持久化。
 *
 * 生命周期：idle → preview → confirming → applied | partial | failed；过期单独标 stale。
 */
data class ScheduleProposal(
    /** 建议唯一 ID（消息内唯一即可，用于定位持久化条目） */
    val proposalId: String,
    /** 生成时间（epoch ms），用于展示与过期判断 */
    val createdAtEpochMs: Long,
    /** 分配项（按日期、开始时间排序） */
    val assignments: List<ScheduleAssignment>,
    /** 未排入项及具体原因 */
    val unplaced: List<ScheduleUnplaced>,
    /** 最早完成日期文本，null 表示没有分配（不伪造） */
    val earliestCompletion: String? = null,
    /** 执行状态 */
    val status: ProposalStatus = ProposalStatus.PREVIEW,
    /** 已成功写入的任务 ID */
    val appliedIds: Set<String> = emptySet(),
    /** 逐项失败原因，key = taskId */
    val failedReasons: Map<String, String> = emptyMap(),
    /** 卡片是否展开（默认只展开第一天，最多 3 条） */
    val expanded: Boolean = false,
) {
    /** 已应用后不可再次应用（旧建议不得覆盖新状态） */
    val canApply: Boolean get() = status == ProposalStatus.PREVIEW || status == ProposalStatus.PARTIAL

    /**
     * 序列化为 JSON，随 chat_conversations.messages 一起存。
     */
    fun toJson(): JSONObject = JSONObject().apply {
        put("proposalId", proposalId)
        put("createdAtEpochMs", createdAtEpochMs)
        put("earliestCompletion", earliestCompletion ?: JSONObject.NULL)
        put("status", status.name)
        put("expanded", expanded)
        put("assignments", JSONArray().apply {
            assignments.forEach { a ->
                put(JSONObject().apply {
                    put("taskId", a.taskId)
                    put("title", a.title)
                    put("date", a.date)
                    put("start", a.start)
                    put("end", a.end)
                    put("minutes", a.minutes)
                    put("originalReminder", a.originalReminder ?: JSONObject.NULL)
                })
            }
        })
        put("unplaced", JSONArray().apply {
            unplaced.forEach { u ->
                put(JSONObject().apply {
                    put("taskId", u.taskId ?: JSONObject.NULL)
                    put("title", u.title)
                    put("reason", u.reason)
                })
            }
        })
        put("appliedIds", JSONArray(appliedIds.toList()))
        put("failedReasons", JSONObject().apply {
            failedReasons.forEach { (k, v) -> put(k, v) }
        })
    }

    companion object {
        /**
         * 从持久化 JSON 还原；解析失败返回 null（消息退化为纯文本，不崩）。
         */
        fun fromJson(obj: JSONObject): ScheduleProposal? {
            return try {
                val assignments = mutableListOf<ScheduleAssignment>()
                obj.optJSONArray("assignments")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        assignments += ScheduleAssignment(
                            taskId = o.optString("taskId", ""),
                            title = o.optString("title", ""),
                            date = o.optString("date", ""),
                            start = o.optString("start", ""),
                            end = o.optString("end", ""),
                            minutes = o.optLong("minutes", 0),
                            originalReminder = o.optString("originalReminder", "").takeIf { it.isNotEmpty() },
                        )
                    }
                }
                val unplaced = mutableListOf<ScheduleUnplaced>()
                obj.optJSONArray("unplaced")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        unplaced += ScheduleUnplaced(
                            taskId = o.optString("taskId", "").takeIf { it.isNotEmpty() },
                            title = o.optString("title", ""),
                            reason = o.optString("reason", ""),
                        )
                    }
                }
                val appliedIds = mutableSetOf<String>()
                obj.optJSONArray("appliedIds")?.let { arr ->
                    for (i in 0 until arr.length()) appliedIds += arr.optString(i)
                }
                val failed = mutableMapOf<String, String>()
                obj.optJSONObject("failedReasons")?.let { o ->
                    o.keys().forEach { k -> failed[k] = o.optString(k) }
                }
                ScheduleProposal(
                    proposalId = obj.optString("proposalId"),
                    createdAtEpochMs = obj.optLong("createdAtEpochMs"),
                    assignments = assignments,
                    unplaced = unplaced,
                    earliestCompletion = obj.optString("earliestCompletion", "").takeIf { it.isNotEmpty() },
                    status = ProposalStatus.entries.find { it.name == obj.optString("status") }
                        ?: ProposalStatus.PREVIEW,
                    appliedIds = appliedIds,
                    failedReasons = failed,
                    expanded = obj.optBoolean("expanded", false),
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

/** 排期卡片状态机（7.10 表格） */
enum class ProposalStatus {
    /** 仅建议，尚未修改任何任务 */
    PREVIEW,
    /** 正在逐项写入 */
    CONFIRMING,
    /** 全部成功 */
    APPLIED,
    /** 部分成功 */
    PARTIAL,
    /** 失败且未写入 */
    FAILED,
    /** 预览之后任务时间被人改动，不得覆盖 */
    STALE,
}

/**
 * 一条排期分配项。
 *
 * @param taskId           稳定任务 ID（写回依据）
 * @param title            仅用于显示
 * @param date             "YYYY-MM-DD"
 * @param start            "HH:MM"
 * @param end              "HH:MM"
 * @param minutes          时长（分钟）
 * @param originalReminder 预览时的 reminder 原值，用于确认前比对
 */
data class ScheduleAssignment(
    val taskId: String,
    val title: String,
    val date: String,
    val start: String,
    val end: String,
    val minutes: Long,
    val originalReminder: String? = null,
)

/** 未排入项 */
data class ScheduleUnplaced(
    val taskId: String?,
    val title: String,
    val reason: String,
)

/**
 * 排期建议卡片（7.10）—— 独立 surface，不放进 AI 气泡里。
 *
 * 折叠态默认只展示第一天的前 3 条，其余折叠为「查看全部 N 项安排」；
 * 展开在当前消息内完成（AnimatedVisibility 高度 + 淡入），不新开浮层。
 *
 * @param proposal        卡片数据
 * @param onApply         点击「应用排期」/「重试未成功项」
 * @param onViewSchedule  点击「查看日程」，传入展示日期
 * @param onOpenTask      点击分配项，打开任务详情
 * @param onExpandToggle  展开/收起
 * @param onFillMinutes   未排入项「去补充」，打开任务详情聚焦预计时长
 * @param onOpenTimeWindows 无可用时间窗时跳设置
 */
@Composable
fun ScheduleProposalCard(
    proposal: ScheduleProposal,
    onApply: () -> Unit,
    onViewSchedule: (String) -> Unit,
    onOpenTask: (String) -> Unit,
    onExpandToggle: (Boolean) -> Unit,
    onFillMinutes: (String) -> Unit,
    onOpenTimeWindows: () -> Unit,
) {
    // 按日期分组，用于「今天 / 明天 / 10月27日 周二」小标题
    val byDate = proposal.assignments.groupBy { it.date }
    val sortedDates = byDate.keys.sorted()

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        // 独立 surface：与普通文本气泡区分开，但不抢 AI 正文的视觉层级
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // ── 标题行：排期建议 + 状态标签 ──
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    LucideIcons.CalendarClock,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.secondary,
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    "排期建议",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.weight(1f))
                // 状态标签：只有真实写入后才显示「已应用」类文案
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                ) {
                    Text(
                        text = when (proposal.status) {
                            ProposalStatus.PREVIEW -> "预览"
                            ProposalStatus.CONFIRMING -> "应用中"
                            ProposalStatus.APPLIED -> "已应用"
                            ProposalStatus.PARTIAL -> "部分完成"
                            ProposalStatus.FAILED -> "未应用"
                            ProposalStatus.STALE -> "需重排"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }

            // ── 副标题：已安排 N 项 · M 项待处理 ──
            val placedCount = proposal.assignments.size
            val pendingCount = proposal.unplaced.size
            Text(
                buildString {
                    append("已安排 $placedCount 项")
                    if (pendingCount > 0) append(" · $pendingCount 项待处理")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // ── 最早完成日期：无分配时不显示，不伪造 ──
            proposal.earliestCompletion?.let { earliest ->
                Text(
                    "预计最早完成：${formatCnDate(earliest)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            // ── 按天分组展示 ──
            val datesToShow = if (proposal.expanded) sortedDates else sortedDates.take(1)
            datesToShow.forEach { date ->
                val items = byDate[date].orEmpty()
                val shown = if (proposal.expanded) items else items.take(3)
                Text(
                    formatDayHeader(date),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                shown.forEach { a ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { onOpenTask(a.taskId) },
                            )
                            .padding(vertical = 5.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 时间用等宽数字，便于纵向扫读
                        Text(
                            "${a.start}–${a.end}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            a.title,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            "${a.minutes} 分钟",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // ── 展开/收起 ──
            val totalShown = if (proposal.expanded) proposal.assignments.size
            else byDate[sortedDates.firstOrNull()].orEmpty().size.coerceAtMost(3)
            if (proposal.assignments.size > totalShown) {
                TextButton(
                    onClick = { onExpandToggle(!proposal.expanded) },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 4.dp, vertical = 0.dp,
                    ),
                ) {
                    Text(
                        if (proposal.expanded) "收起安排" else "查看全部 ${proposal.assignments.size} 项安排",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }

            // ── 未排入区：中性信息块，不用红色 ──
            if (proposal.unplaced.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        proposal.unplaced.forEach { u ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "${u.title} — ${u.reason}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                )
                                // 「去补充」：缺时长的任务给一条直达路径
                                if (u.reason.contains("expected_minutes") && u.taskId != null) {
                                    TextButton(
                                        onClick = { onFillMinutes(u.taskId) },
                                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                            horizontal = 6.dp, vertical = 0.dp,
                                        ),
                                    ) {
                                        Text("去补充", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ── 逐项失败原因（partial/failed 时） ──
            if (proposal.failedReasons.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    proposal.failedReasons.forEach { (taskId, reason) ->
                        val title = proposal.assignments.find { it.taskId == taskId }?.title ?: taskId
                        Text(
                            "$title：$reason",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // ── 底部说明 + 操作按钮 ──
            val statusText = when (proposal.status) {
                ProposalStatus.PREVIEW -> "确认后才会修改任务时间"
                ProposalStatus.CONFIRMING -> "正在应用…"
                ProposalStatus.APPLIED -> "已更新 ${proposal.appliedIds.size} 项安排"
                ProposalStatus.PARTIAL ->
                    "已更新 ${proposal.appliedIds.size} 项，${proposal.failedReasons.size} 项未更新"
                ProposalStatus.FAILED -> "未能应用排期，任务时间未修改"
                ProposalStatus.STALE -> "部分任务时间已变化，请重新排期"
            }
            Text(
                statusText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    // 零分配：主按钮变「调整时间窗」，不显示可应用的假成功按钮
                    proposal.assignments.isEmpty() -> {
                        Button(
                            onClick = onOpenTimeWindows,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f),
                        ) { Text("调整时间窗") }
                    }
                    // 已应用：只读 + 查看日程
                    proposal.status == ProposalStatus.APPLIED -> {
                        Button(
                            onClick = { sortedDates.firstOrNull()?.let(onViewSchedule) },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f),
                        ) { Text("查看日程") }
                    }
                    // stale：不能应用旧建议
                    proposal.status == ProposalStatus.STALE -> {
                        Button(
                            onClick = onApply,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f),
                        ) { Text("重新排期") }
                    }
                    else -> {
                        Button(
                            onClick = onApply,
                            // confirming 期间禁用，防重复提交
                            enabled = proposal.status != ProposalStatus.CONFIRMING,
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                            ),
                            modifier = Modifier.weight(1f),
                        ) {
                            if (proposal.status == ProposalStatus.CONFIRMING) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("正在应用…")
                            } else {
                                Text(
                                    if (proposal.status == ProposalStatus.PARTIAL) "重试未成功项"
                                    else "应用排期",
                                )
                            }
                        }
                        TextButton(
                            onClick = { sortedDates.firstOrNull()?.let(onViewSchedule) },
                            modifier = Modifier.weight(1f),
                        ) { Text("查看日程") }
                    }
                }
            }
        }
    }
}

/** "2026-10-26" → "10月26日"；解析失败原样返回 */
private fun formatCnDate(iso: String): String {
    return try {
        val d = LocalDate.parse(iso, DateTimeFormatter.ISO_LOCAL_DATE)
        "${d.monthValue}月${d.dayOfMonth}日"
    } catch (_: Exception) {
        iso
    }
}

/**
 * 日期小标题："今天 · 10月26日" / "明天 · 10月27日" / "10月28日 周三"。
 *
 * 用「今天/明天」帮助快速定位，超过两天则给星期几。
 */
private fun formatDayHeader(iso: String): String {
    return try {
        val d = LocalDate.parse(iso, DateTimeFormatter.ISO_LOCAL_DATE)
        val today = LocalDate.now()
        val base = "${d.monthValue}月${d.dayOfMonth}日"
        when {
            d == today -> "今天 · $base"
            d == today.plusDays(1) -> "明天 · $base"
            else -> "$base ${weekdayCn(d)}"
        }
    } catch (_: Exception) {
        iso
    }
}

/** 星期中文名 */
private fun weekdayCn(d: LocalDate): String = when (d.dayOfWeek.value) {
    1 -> "周一"
    2 -> "周二"
    3 -> "周三"
    4 -> "周四"
    5 -> "周五"
    6 -> "周六"
    else -> "周日"
}
