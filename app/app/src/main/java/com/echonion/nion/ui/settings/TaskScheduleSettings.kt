package com.echonion.nion.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.echonion.nion.ui.icons.LucideIcons
import com.echonion.nion.ui.task.SmartListDef
import com.echonion.nion.ui.task.SmartLists
import com.echonion.nion.ui.task.TimeWindow
import com.echonion.nion.ui.task.TimeWindows

/**
 * 智能清单设置卡片（7.12）。
 *
 * 默认折叠，摘要显示「已启用 N 项」；展开后六项各一行，
 * 含名称、时间口径与启停开关。今天与收集箱固定启用并用锁图标 + 「默认」标记，
 * 不提供 Switch —— 避免出现空侧栏或「操作后报错」的假开关。
 *
 * @param smartLists   全部内置智能清单（注册表顺序）
 * @param enabledIds   当前启用集合
 * @param onToggle     切换启用状态
 */
@Composable
fun SmartListsSettingsCard(
    smartLists: List<SmartListDef>,
    enabledIds: Set<String>,
    onToggle: (String, Boolean) -> Unit,
) {
    // 默认折叠，符合「设置项多时先给摘要」的信息层级
    var expanded by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) {
        Column {
            // 折叠态标题行
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { expanded = !expanded },
                    )
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    LucideIcons.ClipboardList,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "智能清单",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "已启用 ${enabledIds.size} 项",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    if (expanded) LucideIcons.ChevronUp else LucideIcons.ChevronDown,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }

            if (expanded) {
                Column(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    smartLists.forEach { def ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    def.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    // 口径说明直接取自注册表，与聚合逻辑同源，不会文档与实现两套
                                    def.settingDescription,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (def.locked) {
                                // 锁定项：显示「默认」+ 锁图标，不给 Switch
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "默认",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(
                                        LucideIcons.Bookmark,
                                        contentDescription = "默认启用，不可关闭",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            } else {
                                Switch(
                                    checked = def.id in enabledIds,
                                    onCheckedChange = { onToggle(def.id, it) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 每日时间窗设置卡片（7.11）。
 *
 * 折叠态显示当前时段摘要；点击打开底部抽屉编辑。
 * 校验在保存前一处完成（TimeWindows.validate），失败定位到具体行。
 *
 * @param windows   当前已保存的时间窗
 * @param onSave    保存回调，传入校验通过的时段列表
 */
@Composable
fun DailyTimeWindowsCard(
    windows: List<TimeWindow>,
    onSave: (List<TimeWindow>) -> Unit,
) {
    var showSheet by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { showSheet = true },
            ),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                LucideIcons.CalendarClock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(24.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "每日时间窗",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    TimeWindows.summary(windows),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                LucideIcons.ChevronRight,
                contentDescription = "打开每日时间窗设置",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }

    if (showSheet) {
        DailyTimeWindowsSheet(
            initial = windows,
            onSave = {
                onSave(it)
                showSheet = false
            },
            onDismiss = { showSheet = false },
        )
    }
}

/**
 * 时间窗编辑抽屉 —— 本地草稿 + 保存前校验（7.11）。
 *
 * 关键约定：
 * - 只有点「保存」才提交，取消/关闭不写库；
 * - 校验失败时保留草稿并定位到出错行；
 * - 保存失败由上层提示，抽屉不自行关闭（此处上层为同步写 settings，几乎不会失败）。
 *
 * @param initial   已保存的时段，作为草稿初值
 * @param onSave    保存回调
 * @param onDismiss 关闭回调
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DailyTimeWindowsSheet(
    initial: List<TimeWindow>,
    onSave: (List<TimeWindow>) -> Unit,
    onDismiss: () -> Unit,
) {
    // 本地草稿：编辑期间不写库
    var draft by remember { mutableStateOf(initial) }
    // 行级错误：索引 + 文案
    var errorIndex by remember { mutableStateOf<Int?>(null) }
    var errorText by remember { mutableStateOf<String?>(null) }
    // 当前正在编辑的行索引；null = 未打开时间选择器
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    // 编辑的是开始还是结束
    var editingStart by remember { mutableStateOf(true) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "每日时间窗",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "每天都使用这些时段，排期不会占用时段外的时间",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            draft.forEachIndexed { index, window ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 开始时间
                    TimeChip(
                        text = window.start,
                        hasError = errorIndex == index,
                        onClick = {
                            editingIndex = index
                            editingStart = true
                        },
                    )
                    Text(
                        "–",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 6.dp),
                    )
                    // 结束时间
                    TimeChip(
                        text = window.end,
                        hasError = errorIndex == index,
                        onClick = {
                            editingIndex = index
                            editingStart = false
                        },
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    // 删除该行
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = {
                                    draft = draft.toMutableList().also { it.removeAt(index) }
                                    errorIndex = null
                                    errorText = null
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            LucideIcons.Trash2,
                            contentDescription = "删除这一时段",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                // 行内错误：直接显示在该行下方，指出具体问题
                if (errorIndex == index && errorText != null) {
                    Text(
                        errorText!!,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            OutlinedButton(
                onClick = {
                    draft = draft + TimeWindows.nextDefault(draft)
                    errorIndex = null
                    errorText = null
                },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("+ 添加时段") }

            Spacer(modifier = Modifier.height(4.dp))
            androidx.compose.material3.Button(
                onClick = {
                    val result = TimeWindows.validate(draft)
                    if (!result.isValid) {
                        errorIndex = result.errorIndex
                        errorText = result.errorMessage
                    } else {
                        // 保存前按开始时间排序，落库顺序即展示顺序
                        onSave(TimeWindows.sorted(draft))
                    }
                },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("保存") }
        }
    }

    // 时间选择器：Material 3 TimePicker，24 小时制
    editingIndex?.let { index ->
        val current = draft.getOrNull(index) ?: return@let
        val initMinutes = if (editingStart) {
            current.startMinutes ?: 540
        } else {
            // 结束时间 "24:00" 打开时按 23:59 展示（TimePicker 不接受 24 点）
            (current.endMinutes ?: 600).coerceAtMost(1439)
        }
        val state = rememberTimePickerState(
            initialHour = initMinutes / 60,
            initialMinute = initMinutes % 60,
            is24Hour = true,
        )
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { editingIndex = null },
            title = { Text(if (editingStart) "开始时间" else "结束时间") },
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(onClick = {
                    val text = TimeWindow.formatMinutes(state.hour * 60 + state.minute)
                    draft = draft.toMutableList().also {
                        val w = it[index]
                        it[index] = if (editingStart) w.copy(start = text) else w.copy(end = text)
                    }
                    errorIndex = null
                    errorText = null
                    editingIndex = null
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { editingIndex = null }) { Text("取消") }
            },
        )
    }
}

/**
 * 时间值按钮 —— 点击打开时间选择器；出错行用 error 色描边（不只用颜色，行下还有文字说明）。
 */
@Composable
private fun TimeChip(text: String, hasError: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        shape = RoundedCornerShape(10.dp),
        color = if (hasError) MaterialTheme.colorScheme.errorContainer
        else MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = if (hasError) MaterialTheme.colorScheme.onErrorContainer
            else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}
