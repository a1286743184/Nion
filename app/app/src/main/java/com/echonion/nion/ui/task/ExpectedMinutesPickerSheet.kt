package com.echonion.nion.ui.task

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.material3.MaterialTheme as M3Theme

/**
 * 子项树节点 —— 拍平后的子孙实体 + 它的视觉层级。
 *
 * 为什么要显式带 depth：TaskItem 是树结构，UI 里为了在单一滚动正文中渲染
 * 又需要拍平；如果每行都从 parentId 反查深度，深层级时就是 O(n²)。
 * 由调用方一次性算好，渲染时就只是读取。
 *
 * @param task  子项实体
 * @param depth 相对当前详情实体的层级（1 = 直接子项）
 */
data class ChildNode(
    val task: TaskItem,
    val depth: Int,
)

/**
 * 预计时长选择抽屉 —— 15/25/45/60/90 快捷项 + 自定义正整数 + 清除。
 *
 * 抽成独立公共组件：新建表单与详情页都要用同一套校验规则，
 * 两处各写一份很容易出现「一边允许 0 分钟、一边不允许」的不一致。
 *
 * @param current   当前值，null = 未设置
 * @param onConfirm 确认回调；传 null 表示清除
 * @param onDismiss 关闭回调
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpectedMinutesPickerSheet(
    current: Long?,
    onConfirm: (Long?) -> Unit,
    onDismiss: () -> Unit,
) {
    // 选中的快捷值；null 表示未设置
    var selected by remember { mutableStateOf(current) }
    // 自定义输入；非空且合法时优先于快捷值
    var customText by remember { mutableStateOf("") }
    val customValue = customText.trim().toLongOrNull()
    // 非法输入（有内容但不是正整数）禁止保存
    val customInvalid = customText.isNotBlank() && (customValue == null || customValue <= 0)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = M3Theme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "预计时长",
                style = M3Theme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "这是预计总工作量，不会限制你的专注计时。",
                style = M3Theme.typography.bodySmall,
                color = M3Theme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(15L, 25L, 45L, 60L, 90L).forEach { preset ->
                    val isSelected = customText.isBlank() && selected == preset
                    Surface(
                        modifier = Modifier
                            .height(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = {
                                    selected = preset
                                    customText = ""
                                },
                            ),
                        shape = RoundedCornerShape(10.dp),
                        color = if (isSelected) M3Theme.colorScheme.secondaryContainer
                        else M3Theme.colorScheme.surfaceContainerHigh,
                    ) {
                        Box(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "$preset 分钟",
                                style = M3Theme.typography.labelLarge,
                                color = if (isSelected) M3Theme.colorScheme.onSecondaryContainer
                                else M3Theme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
            OutlinedTextField(
                value = customText,
                onValueChange = { input ->
                    // 只收数字：用户误输字母时直接过滤，而不是等他点保存才报错
                    customText = input.filter { it.isDigit() }.take(4)
                },
                label = { Text("自定义（分钟）") },
                isError = customInvalid,
                supportingText = if (customInvalid) {
                    { Text("请输入大于 0 的整数分钟") }
                } else null,
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { onConfirm(customValue ?: selected) },
                enabled = !customInvalid,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) { Text("保存") }
            // 清除与保存分开：避免「保存空值」被误解成清除
            TextButton(
                onClick = { onConfirm(null) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("清除") }
        }
    }
}
