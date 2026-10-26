package com.echonion.nion.ui.task

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.unit.dp
import com.echonion.nion.ui.icons.LucideIcons

/**
 * 长按操作菜单（7.7）—— 用模态底部抽屉，不做贴近手指的小上下文菜单。
 *
 * 为什么用抽屉：手机上更容易触达（尤其单手/大屏），也能自然容纳「移动清单」
 * 这类需要二级选择的动作；贴近手指的小菜单在拖拽手势旁边还容易误触。
 *
 * 操作顺序（首版定稿）：
 * 1. 移动到清单（二级单选）
 * 2. 更改分组（仅在选定真实清单后出现，含「未分组」）
 * 3. 转为笔记 / 转为任务（按当前 kind 只显示反向项）
 * 4. 选择多项（保留既有能力入口）
 * 5. 删除（弱分隔后的 error 文本项，不用红色实心按钮吸引注意）
 *
 * @param task          目标实体
 * @param checklists    可选的真实清单（智能清单只能作为来源，不能作为目标）
 * @param currentGroups 目标清单下的分组（切换清单时由调用方重新传入）
 * @param onRequestGroups 用户选中某个清单后，要求加载该清单的分组
 * @param onMove        提交移动：checklistId + groupId（null = 未分组）
 * @param onSwitchKind  类型互转，传入目标 kind
 * @param onEnterSelectionMode 进入多选模式
 * @param onDelete      点删除（调用方负责收起菜单后再弹确认框）
 * @param onDismiss     关闭抽屉
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntityActionsSheet(
    task: TaskItem,
    checklists: List<ChecklistItem>,
    currentGroups: List<GroupItem>,
    onRequestGroups: (String) -> Unit,
    onMove: (checklistId: String, groupId: String?) -> Unit,
    onSwitchKind: (String) -> Unit,
    onEnterSelectionMode: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    // 展开中的二级面板：null = 主菜单，"checklist" = 清单选择，"group" = 分组选择
    var subPanel by remember { mutableStateOf<String?>(null) }
    // 已选目标清单（在 checklist 面板里点击后暂存，用于展示分组面板）
    var targetChecklistId by remember { mutableStateOf<String?>(null) }
    val targetChecklist = checklists.find { it.id == targetChecklistId }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // 标题：实体名 + 类型说明（一眼看出这条是任务还是笔记）
            Column(modifier = Modifier.padding(bottom = 8.dp)) {
                Text(
                    task.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                )
                Text(
                    if (task.isNote) "笔记" else "任务",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            when (subPanel) {
                "checklist" -> ChecklistPickerList(
                    checklists = checklists,
                    currentChecklistId = targetChecklistId ?: task.categoryId,
                    onSelect = { id ->
                        targetChecklistId = id
                        // 选中清单后加载该清单的分组，进入分组面板
                        onRequestGroups(id)
                        subPanel = "group"
                    },
                    onBack = { subPanel = null },
                )
                "group" -> GroupPickerList(
                    groups = currentGroups,
                    currentGroupId = task.groupId,
                    onSelect = { groupId ->
                        targetChecklistId?.let { onMove(it, groupId) }
                        onDismiss()
                    },
                    onBack = { subPanel = "checklist" },
                )
                else -> {
                    // ── 主菜单 ──
                    ActionRow(
                        icon = LucideIcons.MapPin,
                        label = "移动到清单",
                        value = checklists.find { it.id == task.categoryId }?.name ?: "未归类",
                        onClick = { subPanel = "checklist" },
                    )
                    // 更改分组：只有实体已在真实清单里才有意义
                    if (task.categoryId != null) {
                        ActionRow(
                            icon = LucideIcons.ClipboardList,
                            label = "更改分组",
                            value = currentGroups.find { it.id == task.groupId }?.name ?: "未分组",
                            onClick = {
                                targetChecklistId = task.categoryId
                                onRequestGroups(task.categoryId)
                                subPanel = "group"
                            },
                        )
                    }
                    // 类型互转：只显示反向项，并附保留说明
                    ActionRow(
                        icon = if (task.isNote) LucideIcons.CircleCheck else LucideIcons.FileText,
                        label = if (task.isNote) "转为任务" else "转为笔记",
                        value = if (task.isNote) "内容与关联保留" else "可再转回，完成状态保留",
                        onClick = {
                            onSwitchKind(if (task.isNote) "task" else "note")
                            onDismiss()
                        },
                    )
                    ActionRow(
                        icon = LucideIcons.SquareCheck,
                        label = "选择多项",
                        value = "进入多选模式",
                        onClick = {
                            onEnterSelectionMode()
                            onDismiss()
                        },
                    )
                    // ── 删除：弱分隔 + error 文本，不用实心红按钮 ──
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 8.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                    TextButton(
                        onClick = {
                            // 先收菜单，再弹确认框（7.7：不叠两层弹窗）
                            onDelete()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            "删除",
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 菜单行 —— 图标 + 标题 + 右侧当前值，整行可点。
 *
 * @param icon    行首图标
 * @param label   动作名称
 * @param value   当前值/说明，显示在右侧（弱化）
 * @param onClick 点击动作
 */
@Composable
private fun ActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 8.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(4.dp))
        Icon(
            LucideIcons.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 清单选择列表（二级面板）—— 只列真实清单。
 *
 * 智能清单（今天/收集箱等）是聚合视图，不能作为移动目标：
 * 让用户把实体「移到今天」在数据层没有对应归属，会得到一个永远空的目标。
 *
 * @param checklists       可选清单
 * @param currentChecklistId 当前所属清单，用于勾选标记
 * @param onSelect         选中清单
 * @param onBack           返回主菜单
 */
@Composable
private fun ChecklistPickerList(
    checklists: List<ChecklistItem>,
    currentChecklistId: String?,
    onSelect: (String) -> Unit,
    onBack: () -> Unit,
) {
    SubPanelHeader(title = "移动到清单", onBack = onBack)
    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        checklists.forEach { cl ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onSelect(cl.id) },
                    )
                    .padding(horizontal = 8.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    cl.name,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                if (cl.id == currentChecklistId) {
                    // 当前清单用文字标记，不只靠颜色（可访问性）
                    Text(
                        "当前",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
        }
    }
}

/**
 * 分组选择列表（二级面板）—— 含「未分组」选项。
 *
 * @param groups       目标清单下的分组
 * @param currentGroupId 当前分组，用于勾选标记
 * @param onSelect     选中分组；null = 未分组
 * @param onBack       返回清单选择
 */
@Composable
private fun GroupPickerList(
    groups: List<GroupItem>,
    currentGroupId: String?,
    onSelect: (String?) -> Unit,
    onBack: () -> Unit,
) {
    SubPanelHeader(title = "更改分组", onBack = onBack)
    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        // 未分组排在最前：目标清单没有合适分组时的自然选择
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { onSelect(null) },
                )
                .padding(horizontal = 8.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "未分组",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            if (currentGroupId == null) {
                Text(
                    "当前",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }
        groups.forEach { g ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onSelect(g.id) },
                    )
                    .padding(horizontal = 8.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    g.name,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                if (g.id == currentGroupId) {
                    Text(
                        "当前",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
        }
        if (groups.isEmpty()) {
            Text(
                "这个清单还没有分组",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            )
        }
    }
}

/**
 * 二级面板标题栏 —— 返回按钮 + 标题。
 *
 * @param title  面板标题
 * @param onBack 返回上一级
 */
@Composable
private fun SubPanelHeader(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.size(36.dp),
            onClick = onBack,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    LucideIcons.ChevronLeft,
                    contentDescription = "返回",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * 删除确认对话框（7.7 删除保护）。
 *
 * 文案必须如实说明保留策略：本次是「子项上移一级」而非级联删除。
 * 默认焦点在「取消」；执行中禁用两个按钮；不需要输入实体名。
 *
 * @param task      待删除实体
 * @param childCount 直接子项数量（0 时不显示数量）
 * @param deleting  是否正在删除（禁用按钮防重复提交）
 * @param onConfirm 确认删除
 * @param onDismiss 取消
 */
@Composable
fun DeleteEntityDialog(
    task: TaskItem,
    childCount: Int,
    deleting: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!deleting) onDismiss() },
        title = {
            Text(
                // 标题按 kind 区分，让用户清楚自己删的是哪种实体
                if (task.isNote) "删除笔记？" else "删除任务？",
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "「${task.name}」",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    if (childCount > 0) {
                        // 保留策略如实说明：只删当前项，子项上移一级
                        "只删除当前项，$childCount 个子项将上移一级。此操作无法撤销。"
                    } else {
                        "此操作无法撤销。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            // 删除用 error 色文本按钮，不用红色实心按钮吸引注意
            TextButton(
                onClick = onConfirm,
                enabled = !deleting,
            ) {
                Text(
                    if (deleting) "正在删除…" else "删除",
                    color = if (deleting) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Medium,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !deleting) {
                Text("取消")
            }
        },
    )
}
