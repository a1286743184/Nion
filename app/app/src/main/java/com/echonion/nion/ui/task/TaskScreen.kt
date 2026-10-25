package com.echonion.nion.ui.task

import com.echonion.nion.ui.notebook.NotebookContent
import com.echonion.nion.ui.notebook.NotebookEditor
import com.echonion.nion.ui.theme.NionAlpha
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.zIndex
import androidx.activity.compose.BackHandler
import kotlinx.coroutines.launch
import com.echonion.nion.ui.components.DualPanelState
import com.echonion.nion.ui.components.TaskDetailOverlay
import com.echonion.nion.ui.components.SharedTaskList
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import com.echonion.nion.ui.icons.LucideIcons

/**
 * 任务主屏幕 —— 显示任务列表 + FAB。
 *
 * 使用 SharedTransitionLayout 实现两套动画系统：
 * 1. FAB ↔ 添加任务表单（sharedBounds，FAD 展开/收回动画）
 * 2. 任务卡片 ↔ 任务详情浮层（sharedElement + AnimatedContent，卡片 morph 展开动画）
 *
 * 两者互斥：同一时间只能展开一个。
 *
 * @param dualState 双面板状态，控制左右侧边栏
 * @param viewModel 任务 ViewModel
 * @param onStartFocus 用户从任务详情点击"开始专注"时触发，传入 (任务ID, 任务标题, 预选时长分钟)
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun TaskScreen(
    dualState: DualPanelState,
    viewModel: TaskViewModel,
    /** 用户从任务详情点击"开始专注"时触发，传入 (任务ID, 任务标题, 预选时长分钟) */
    onStartFocus: (taskId: String, taskTitle: String, durationMinutes: Int) -> Unit = { _, _, _ -> },
    /**
     * 外部请求打开某实体的详情/编辑器（通知点击、聊天排期卡跳转等）。
     * 非 null 时本组件会切到该实体所在清单并展开它，随后通过
     * [onOpenEntityConsumed] 通知调用方清空，保证同一个请求只消费一次。
     */
    openEntityRequest: String? = null,
    onOpenEntityConsumed: () -> Unit = {},
) {
    var showAddSheet by remember { mutableStateOf(false) }
    var showAddNote by remember { mutableStateOf(false) }
    var expandedTaskId by remember { mutableStateOf<String?>(null) }

    // ── 消费外部「打开实体」请求（7.8 openEntity 语义的落地点）──
    // 目标任务可能不在当前清单里（例如用户停在「今天」，通知指向工作清单里的任务），
    // 直接设 expandedTaskId 只会得到空白浮层。所以先查实体归属，必要时先切清单。
    LaunchedEffect(openEntityRequest) {
        val targetId = openEntityRequest ?: return@LaunchedEffect
        try {
            val entity = viewModel.findEntityById(targetId)
            if (entity == null) {
                // 实体已被删除（可能是旧的提醒残留）：不打开空白浮层
                return@LaunchedEffect
            }
            val ownerChecklist = entity.categoryId
            if (ownerChecklist != null && ownerChecklist != viewModel.activeChecklistId) {
                viewModel.setActiveChecklist(ownerChecklist)
            }
            expandedTaskId = targetId
        } finally {
            // 无论成功与否都消费掉请求，避免用户手动关掉详情后又被弹回来
            onOpenEntityConsumed()
        }
    }

    // 笔记搜索关键词：null=不搜索，非空=按关键词过滤笔记列表
    var noteSearchQuery by remember { mutableStateOf<String?>(null) }

    // ===== 附件相关状态 =====
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 新建任务时的临时附件列表（任务创建前暂存在内存中）
    var pendingAttachments by remember { mutableStateOf<List<AttachmentUiItem>>(emptyList()) }
    // 记录临时附件的完整信息（用于创建任务后批量写入数据库）
    var pendingAttachmentData by remember { mutableStateOf<List<PickedFileInfo>>(emptyList()) }
    // 当前查看详情的任务的附件列表
    var detailAttachments by remember { mutableStateOf<List<AttachmentUiItem>>(emptyList()) }
    // 图片预览状态：非 null 时显示全屏预览
    var previewAttachment by remember { mutableStateOf<AttachmentUiItem?>(null) }

    // 新建任务用的附件选择器
    val addAttachmentPicker = rememberAttachmentPicker { fileInfo ->
        val id = java.util.UUID.randomUUID().toString()
        val item = AttachmentUiItem(
            id = id,
            fileName = fileInfo.fileName,
            filePath = fileInfo.filePath,
            mimeType = fileInfo.mimeType,
            fileSize = fileInfo.fileSize,
            isImage = fileInfo.mimeType.startsWith("image/"),
        )
        pendingAttachments = pendingAttachments + item
        pendingAttachmentData = pendingAttachmentData + fileInfo
    }

    // 任务详情用的附件选择器
    val detailAttachmentPicker = rememberAttachmentPicker { fileInfo ->
        expandedTaskId?.let { taskId ->
            viewModel.addAttachment(taskId, fileInfo.filePath, fileInfo.fileName, fileInfo.mimeType, fileInfo.fileSize)
            // 刷新附件列表
            scope.launch {
                detailAttachments = viewModel.getAttachments(taskId)
            }
        }
    }

    // 当任务详情打开时，加载该任务的附件列表
    LaunchedEffect(expandedTaskId) {
        if (expandedTaskId != null) {
            detailAttachments = viewModel.getAttachments(expandedTaskId!!)
        } else {
            detailAttachments = emptyList()
        }
    }

    // 当新建任务浮层关闭时，清空临时附件
    LaunchedEffect(showAddSheet) {
        if (!showAddSheet) {
            pendingAttachments = emptyList()
            pendingAttachmentData = emptyList()
        }
    }

    // 拖拽排序状态需保持在 Crossfade 外部，跨清单切换时不丢失
    val reorderableItems = remember { mutableStateListOf<FlatTaskItem>() }

    // backdropBlur: 展开时模糊任务列表，收回时恢复清晰
    // 同时感知 FAD 展开和任务详情展开
    val blurPx by animateFloatAsState(
        targetValue = if (showAddSheet || showAddNote || expandedTaskId != null) 25f else 0f,
        animationSpec = tween(300),
        label = "backdropBlur",
    )

    /**
     * 递归从任务树中按 ID 查找任务。
     * 用于在 AnimatedContent 的 detail 分支中获取被展开任务的完整数据。
     */
    fun findTaskById(tasks: List<TaskItem>, id: String): TaskItem? {
        for (task in tasks) {
            if (task.id == id) return task
            val found = findTaskById(task.subtasks, id)
            if (found != null) return found
        }
        return null
    }

    // 任务详情 overlay 返回拦截：expandedTaskId 非空时，系统返回手势关闭详情而非退出页面
    BackHandler(enabled = expandedTaskId != null) {
        expandedTaskId = null
    }
    // 添加任务 overlay 返回拦截
    BackHandler(enabled = showAddSheet) {
        showAddSheet = false
    }
    // 添加笔记 overlay 返回拦截
    BackHandler(enabled = showAddNote) {
        showAddNote = false
    }

    // SharedTransitionLayout：为 sharedBounds（FAB）+ sharedElement（任务卡片）两套动画提供容器
    SharedTransitionLayout(modifier = Modifier.fillMaxSize()) {
        // AnimatedContent：任务列表 ↔ 任务详情浮层的 morph 过渡
        AnimatedContent(
            targetState = expandedTaskId,
            transitionSpec = {
                if (targetState != null) {
                    // 展开：详情浮层立即出现（不淡入），只让任务列表淡出
                    // 避免 detail 分支的 scrim 也在淡入期间透明，导致底层白色列表透出
                    (EnterTransition.None togetherWith fadeOut(tween(250, easing = FastOutSlowInEasing)))
                        .using(SizeTransform(clip = false))
                } else {
                    // 收回：任务列表快速淡入，详情浮层缓慢淡出
                    (fadeIn(tween(250, easing = FastOutSlowInEasing))
                        togetherWith fadeOut(tween(400, easing = FastOutSlowInEasing)))
                        .using(SizeTransform(clip = false))
                }
            },
            label = "taskDetail",
        ) { taskId ->
            if (taskId != null) {
                if (viewModel.isNotebookView) {
                    // 笔记编辑器：笔记型清单中点击笔记后展开的全屏 Markdown 编辑器
                    val note = findTaskById(viewModel.tasks, taskId)
                    if (note != null) {
                        // 可关联的任务列表：加载所有任务型清单中的顶层任务
                        var allLinkableTasks by remember { mutableStateOf<List<TaskItem>>(emptyList()) }
                        // 加载此笔记的关联任务和所有可选任务
                        LaunchedEffect(taskId) {
                            viewModel.loadLinkedTasks(taskId)
                            allLinkableTasks = viewModel.getAllTasks()
                        }
                        NotebookEditor(
                            note = note,
                            linkedTasks = viewModel.linkedTasks,
                            allTasks = allLinkableTasks,
                            onUpdateTitle = { newTitle ->
                                viewModel.updateTask(taskId, name = newTitle)
                            },
                            onUpdateContent = { newContent ->
                                viewModel.updateTask(taskId, description = newContent)
                            },
                            onLinkTask = { taskToLinkId ->
                                viewModel.linkNoteToTask(taskId, taskToLinkId)
                            },
                            onUnlinkTask = { taskToUnlinkId ->
                                viewModel.unlinkNoteFromTask(taskId, taskToUnlinkId)
                            },
                            // 与列表中笔记卡片共享 bounds，实现卡片放大/缩回的 morph 动画
                            // key 与 TaskDetailOverlay 一致格式 "task_detail_$taskId"
                            sharedElementModifier = Modifier.sharedElement(
                                sharedContentState = rememberSharedContentState("task_detail_$taskId"),
                                animatedVisibilityScope = this@AnimatedContent,
                                boundsTransform = { _, _ ->
                                    spring(
                                        dampingRatio = 0.8f,
                                        stiffness = Spring.StiffnessMediumLow,
                                    )
                                },
                            ),
                            onDismiss = { expandedTaskId = null },
                        )
                    }
                } else {
                    // 任务详情浮层：从任务卡片位置 morph 展开
                    val task = findTaskById(viewModel.tasks, taskId)
                    if (task != null) {
                    TaskDetailOverlay(
                        task = task,
                        onDismiss = { expandedTaskId = null },
                        onDelete = { expandedTaskId = null; viewModel.deleteTask(task.id) },
                        onCreateSubtask = { title, priority -> viewModel.createSubtask(taskId, title, priority); expandedTaskId = null },
                        onUpdateNotes = { notes ->
                            viewModel.updateTask(taskId, description = notes)
                        },
                        /** 任务标题重命名：调用 ViewModel 更新标题 */
                        onRename = { newName ->
                            viewModel.updateTask(taskId, name = newName)
                        },
                        onUpdateRecurrence = { rule, time ->
                            viewModel.updateRecurrence(taskId, rule, time)
                        },
                        onRemoveRecurrence = {
                            viewModel.removeRecurrence(taskId)
                        },
                        onUpdateReminder = { reminder ->
                            viewModel.updateReminder(taskId, reminder)
                        },
                        /** 点击开始专注：直接导航到专注页，不关闭详情浮层，避免任务列表闪现 */
                        onStartFocus = { durationMinutes ->
                            onStartFocus(task.id, task.name, durationMinutes)
                        },
                        sharedElementModifier = Modifier.sharedElement(
                            sharedContentState = rememberSharedContentState("task_detail_$taskId"),
                            animatedVisibilityScope = this@AnimatedContent,
                            boundsTransform = { _, _ ->
                                spring(
                                    dampingRatio = 0.8f,
                                    stiffness = Spring.StiffnessMediumLow,
                                )
                            },
                        ),
                        /** 附件相关参数 */
                        attachments = detailAttachments,
                        onPickImage = { detailAttachmentPicker.pickImage() },
                        onPickFile = { detailAttachmentPicker.pickFile() },
                        onRemoveAttachment = { attachmentId ->
                            viewModel.removeAttachment(attachmentId)
                            // 刷新附件列表
                            scope.launch {
                                detailAttachments = viewModel.getAttachments(taskId)
                            }
                        },
                        onPreviewImage = { previewAttachment = it },
                    )
                    }
                }
            } else {
                // 统一的列表 + FAB + 添加浮层（任务型和笔记型清单共用）
                Box(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.graphicsLayer {
                        if (blurPx > 0f && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                            renderEffect = android.graphics.RenderEffect
                                .createBlurEffect(blurPx, blurPx, android.graphics.Shader.TileMode.CLAMP)
                                .asComposeRenderEffect()
                        }
                    }) {
                        val detailAnimatedScope = this@AnimatedContent
                        TaskScreenContent(
                            dualState = dualState,
                            viewModel = viewModel,
                            onTaskClick = { expandedTaskId = it.id },
                            taskSharedModifier = { targetId ->
                                Modifier.sharedElement(
                                    sharedContentState = rememberSharedContentState("task_detail_$targetId"),
                                    animatedVisibilityScope = detailAnimatedScope,
                                    boundsTransform = { _, _ ->
                                        spring(
                                            dampingRatio = 0.8f,
                                            stiffness = Spring.StiffnessMediumLow,
                                        )
                                    },
                                )
                            },
                            isNotebook = viewModel.isNotebookView,
                            fab = {
                                AnimatedVisibility(
                                    visible = !showAddSheet && !showAddNote,
                                    enter = fadeIn(tween(250, easing = FastOutSlowInEasing)),
                                    exit = fadeOut(tween(180, easing = FastOutSlowInEasing)),
                                ) {
                                    FloatingActionButton(
                                        onClick = {
                                            // 单一入口（7.6）：一律打开统一表单，
                                            // 默认类型由清单语境决定（笔记型清单默认笔记），
                                            // 用户在表单里可随时切换，不需要认识两套按钮
                                            showAddSheet = true
                                        },
                                        modifier = Modifier.sharedBounds(
                                            sharedContentState = rememberSharedContentState("fab"),
                                            animatedVisibilityScope = this@AnimatedVisibility,
                                            boundsTransform = { _, _ ->
                                                spring(
                                                    dampingRatio = 0.8f,
                                                    stiffness = Spring.StiffnessMediumLow,
                                                )
                                            },
                                        ),
                                        containerColor = MaterialTheme.colorScheme.primary,
                                        contentColor = MaterialTheme.colorScheme.onPrimary,
                                        elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 0.dp, hoveredElevation = 0.dp),
                                        shape = RoundedCornerShape(16.dp),
                                    ) { Icon(LucideIcons.Plus, contentDescription = if (viewModel.isNotebookView) "添加笔记" else "添加任务") }
                                }
                            },
                            reorderableItems = reorderableItems,
                        )
                    } // end blur Box

                    // 添加任务浮层（任务型清单）
                    AnimatedVisibility(
                        visible = showAddSheet,
                        enter = fadeIn(tween(300, easing = FastOutSlowInEasing)),
                        exit = fadeOut(tween(400, easing = FastOutSlowInEasing)),
                    ) {
                        AddTaskOverlay(
                            onDismiss = { showAddSheet = false },
                            // 默认类型由清单语境给出：笔记型清单默认 note，其余（含智能清单）默认 task
                            initialKind = if (viewModel.isNotebookView) "note" else "task",
                            onAdd = { kind, title, desc, priority, recRule, recTime, reminder, minutes ->
                                val pending = pendingAttachmentData.toList()
                                viewModel.createEntity(
                                    kind = kind,
                                    name = title,
                                    description = desc,
                                    priority = priority,
                                    expectedMinutes = minutes,
                                    recurrenceRule = recRule,
                                    recurrenceReminderTime = recTime,
                                    reminder = reminder,
                                ) { newId ->
                                    if (pending.isNotEmpty()) {
                                        viewModel.commitPendingAttachments(newId, pending)
                                    }
                                    // 笔记创建成功后直接打开编辑器（7.6）；任务回列表
                                    if (kind == "note") expandedTaskId = newId
                                }
                                showAddSheet = false
                            },
                            sharedBoundsModifier = Modifier.sharedBounds(
                                sharedContentState = rememberSharedContentState("fab"),
                                animatedVisibilityScope = this@AnimatedVisibility,
                                boundsTransform = { _, _ ->
                                    spring(
                                        dampingRatio = 0.8f,
                                        stiffness = Spring.StiffnessMediumLow,
                                    )
                                },
                            ),
                            pendingAttachments = pendingAttachments,
                            onPickImage = { addAttachmentPicker.pickImage() },
                            onPickFile = { addAttachmentPicker.pickFile() },
                            onRemoveAttachment = { id ->
                                val idx = pendingAttachments.indexOfFirst { it.id == id }
                                if (idx >= 0) {
                                    pendingAttachments = pendingAttachments.toMutableList().also { it.removeAt(idx) }
                                    pendingAttachmentData = pendingAttachmentData.toMutableList().also { it.removeAt(idx) }
                                }
                            },
                            onPreviewImage = { previewAttachment = it },
                        )
                    }

                    // 添加笔记浮层（笔记型清单）
                    AnimatedVisibility(
                        visible = showAddNote,
                        enter = fadeIn(tween(300, easing = FastOutSlowInEasing)),
                        exit = fadeOut(tween(400, easing = FastOutSlowInEasing)),
                    ) {
                        AddNoteOverlay(
                            onDismiss = { showAddNote = false },
                            onAdd = { title, content ->
                                viewModel.createNote(title, content) { newNoteId ->
                                    expandedTaskId = newNoteId
                                }
                                showAddNote = false
                            },
                            sharedBoundsModifier = Modifier.sharedBounds(
                                sharedContentState = rememberSharedContentState("fab"),
                                animatedVisibilityScope = this@AnimatedVisibility,
                                boundsTransform = { _, _ ->
                                    spring(
                                        dampingRatio = 0.8f,
                                        stiffness = Spring.StiffnessMediumLow,
                                    )
                                },
                            ),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 任务主界面内容 —— 顶栏 + 任务列表 + FAB。
 *
 * FAB 通过 fab 参数传入，由上层 TaskScreen 用 AnimatedVisibility + sharedBounds 包裹，
 * 任务列表始终渲染，不受 FAB 展开/收回动画影响。
 *
 * @param dualState 双面板状态
 * @param viewModel 任务 ViewModel
 * @param onTaskClick 任务点击回调，打开任务详情
 * @param taskSharedModifier 为每个任务卡片生成 sharedElement modifier 的函数，用于详情展开 morph 动画
 * @param fab FAB 组件，由上层提供（包含 sharedBounds 动画逻辑）
 * @param reorderableItems 外部传入的可重排项列表，跨 AnimatedContent 过渡保留拖拽状态
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskScreenContent(
    dualState: DualPanelState,
    viewModel: TaskViewModel,
    onTaskClick: (TaskItem) -> Unit,
    taskSharedModifier: @Composable (String) -> Modifier,
    fab: @Composable () -> Unit,
    /** 外部传入，跨 AnimatedContent 过渡保留 */
    reorderableItems: SnapshotStateList<FlatTaskItem>,
    /** 笔记模式：true 时列表标题显示"笔记"，SharedTaskCard 显示笔记图标 */
    isNotebook: Boolean = false,
) {
    // ============ 完成动画协调状态 ============
    // animatingDoneIds：正在播放完成动画的任务 ID。
    // 这些任务 visualDone=true（UI 呈现已完成），但 model.isDone 还是 false。
    // 动画结束后通过 onCompletionAnimEnd 回调移除并调用真正的 toggleDone，任务才移入已完成区。
    val animatingDoneIds = remember { mutableStateListOf<String>() }
    // burstTaskIds：动画期间需要显示光芒射线爆发的任务 ID（每天第一个/最后一个，规则见 handleToggleDone）
    val burstTaskIds = remember { mutableStateListOf<String>() }
    // grandBurstTaskIds：盛大爆发的任务 ID（仅"今天"清单清空全部待办时，射线翻倍）
    val grandBurstTaskIds = remember { mutableStateListOf<String>() }

    // 用 derivedStateOf 把 SnapshotStateList 转成 Set 传给 SharedTaskList，
    // 避免每次重组都 toSet()，只在列表变化时重算
    val playCompletionAnimIds by remember { derivedStateOf { animatingDoneIds.toSet() } }
    val burstTaskIdSet by remember { derivedStateOf { burstTaskIds.toSet() } }
    val grandBurstTaskIdSet by remember { derivedStateOf { grandBurstTaskIds.toSet() } }

    /**
     * 包装后的 toggleDone 回调 —— 实现完成动画的延迟乐观更新。
     *
     * - 取消完成（done→todo）：立即调用 viewModel.toggleDone，保持现状（瞬间切换）。
     * - 完成（todo→done）：不立即写库，先把任务加入 animatingDoneIds 启动动画；
     *   同时根据"第一个/最后一个"规则决定是否加入 burstTaskIds。
     *   真正的写库在动画结束后的 handleCompletionAnimEnd 中进行。
     * - 动画期间禁用连点（同一任务 id 在 animatingDoneIds 中时忽略）。
     */
    val handleToggleDone: (TaskItem) -> Unit = { task ->
        if (task.id !in animatingDoneIds) {
            if (task.isDone) {
                // 取消完成：立即切换，保持现状
                viewModel.toggleDone(task)
            } else {
                // 完成：启动动画，延迟写库
                // 判断"第一个"：当前已完成列表为空
                val isFirst = viewModel.doneTasks.isEmpty()
                // 判断"最后一个"（完成后 todo 区清空）：
                // 收集完成该任务后会从 todo 区移除的所有任务 id（任务本身；若是主任务则含全部子任务后代）
                val affectedIds = mutableSetOf(task.id)
                val topLevel = viewModel.tasks.find { it.id == task.id }
                if (topLevel != null) {
                    fun collectAll(t: TaskItem) {
                        for (sub in t.subtasks) {
                            affectedIds.add(sub.id)
                            collectAll(sub)
                        }
                    }
                    collectAll(topLevel)
                }
                val isLast = viewModel.flatTodoTasks.none { it.task.id !in affectedIds }
                // 爆发规则："今天"清单第一个或最后一个；其他清单仅第一个
                val burst = when (viewModel.activeChecklistId) {
                    TaskViewModel.TODAY_ID -> isFirst || isLast
                    else -> isFirst
                }
                // 盛大爆发：仅"今天"清单的"最后一个"（清空全部待办）
                val grand = viewModel.activeChecklistId == TaskViewModel.TODAY_ID && isLast
                animatingDoneIds.add(task.id)
                if (burst) {
                    burstTaskIds.add(task.id)
                    if (grand) grandBurstTaskIds.add(task.id)
                }
            }
        }
    }

    /**
     * 完成动画结束回调 —— 移除各动画集合中的任务 id，并调用真正的 toggleDone 写入完成状态。
     *
     * 此时完成动画（对勾绘制/删除线/爆发）已播完，viewModel.toggleDone 触发的乐观更新
     * 会让任务经 animateItem 从 todo 区滑入已完成区，视觉上"先在原位完成，再移走"。
     */
    val handleCompletionAnimEnd: (TaskItem) -> Unit = { task ->
        animatingDoneIds.remove(task.id)
        burstTaskIds.remove(task.id)
        grandBurstTaskIds.remove(task.id)
        viewModel.toggleDone(task)
    }

    Scaffold(
        contentWindowInsets = WindowInsets.statusBars,
        topBar = {
            if (viewModel.isSelectionMode) {
                SelectionTopBar(
                    selectedCount = viewModel.selectedTaskIds.size,
                    onClearSelection = { viewModel.clearSelection() },
                    onDeleteSelected = { viewModel.deleteSelectedTasks() },
                )
            } else {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = {
                            if (dualState.isRightOpen) dualState.closeRight()
                            dualState.toggleLeft()
                        }) {
                            Icon(LucideIcons.Menu, contentDescription = "清单", tint = MaterialTheme.colorScheme.onSurface)
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            if (dualState.isLeftOpen) dualState.closeLeft()
                            dualState.toggleRight()
                        }) {
                            Icon(LucideIcons.MessageCircle, contentDescription = "伙伴", tint = MaterialTheme.colorScheme.onSurface)
                        }
                    },
                    title = {
                        Column {
                            Text(viewModel.activeChecklistName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Text(
                                // 统计口径见 TaskViewModel.statsSubtitle（7.4）：
                                // 含全部子孙实体、笔记按 kind 计数、已完成只含任务
                                viewModel.statsSubtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                )
            }
        },
        floatingActionButton = fab,
    ) { innerPadding ->
        // 清单切换 Crossfade 动画：切换 activeChecklistId 时内容淡入淡出
        Crossfade(
            targetState = viewModel.activeChecklistId,
            animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
            label = "checklistSwitch",
            modifier = Modifier.padding(innerPadding),
        ) {
            // 分组标签栏：选中真实清单时显示（"今天"和"收集箱"是虚拟视图，无分组）
            val showGroupBar = it != null
                    && it != TaskViewModel.TODAY_ID
                    && it != TaskViewModel.INBOX_ID
            // 每个清单独立持有自己的滚动状态，切换清单时自动从顶部开始
            val listState = rememberLazyListState()
            Column(modifier = Modifier.fillMaxSize()) {
                if (showGroupBar) {
                    /**
                     * 分组标签栏 —— 水平滚动的分组选择器。
                     * 显示"全部" + 各分组名称，点击切换筛选。
                     * 最右侧有"+"按钮用于新建分组。
                     */
                    GroupTabBar(
                        groups = viewModel.groups,
                        activeGroupId = viewModel.activeGroupId,
                        onGroupSelected = { viewModel.setActiveGroup(it) },
                        onCreateGroup = { viewModel.createGroup(it) },
                        onDeleteGroup = { viewModel.deleteGroup(it) },
                    )
                }
                if (viewModel.tasks.isEmpty()) {
                    // 空态文案按场景提供（纯任务/纯笔记/混合/今天/收集箱各不相同）
                    val (emptyTitle, emptyHint, emptyAction) = viewModel.emptyStateText
                    EmptyTaskView(
                        modifier = Modifier.weight(1f),
                        title = emptyTitle,
                        hint = emptyHint,
                        actionHint = emptyAction,
                        // 笔记型语境用文档图标，其余用对勾圆
                        icon = if (viewModel.isNotebookView) LucideIcons.FileText else LucideIcons.CircleCheck,
                    )
                } else {
                    SharedTaskList(
                        todoItems = viewModel.flatTodoTasks,
                        doneItems = viewModel.flatDoneTasks,
                        // 用包装后的 handleToggleDone 替代直接 viewModel.toggleDone，
                        // 实现完成时"先动画后写库"的延迟乐观更新
                        onToggleDone = handleToggleDone,
                        onTaskClick = onTaskClick,
                        onToggleSelection = { viewModel.toggleSelection(it) },
                        reorderCallback = { draggedId, newParentId, siblingIds -> viewModel.moveAndReorderTasks(draggedId, newParentId, siblingIds) },
                        isSelectionMode = viewModel.isSelectionMode,
                        selectedIds = viewModel.selectedTaskIds,
                        taskSharedModifier = taskSharedModifier,
                        reorderableItems = reorderableItems,
                        listState = listState,
                        innerPadding = androidx.compose.foundation.layout.PaddingValues(),
                        modifier = Modifier.weight(1f),
                        onToggleCollapse = { viewModel.toggleCollapse(it) },
                        isNotebook = isNotebook,
                        // 完成动画协调参数：由上面的状态集合驱动
                        playCompletionAnimIds = playCompletionAnimIds,
                        burstTaskIds = burstTaskIdSet,
                        grandBurstTaskIds = grandBurstTaskIdSet,
                        onCompletionAnimEnd = handleCompletionAnimEnd,
                    )
                }
            }
        }
    }
}

/**
 * 提醒入口行 —— 显示当前提醒时间，点击后导航到全屏 REMINDER 面板。
 *
 * 轻量级组件：仅显示 Alarm 图标 + "提醒" 文字 + 当前值（"设置提醒" / "6月15日 14:30"），
 * 点击行为由外部 onClick 回调处理（通常切换到 REMINDER 面板）。
 *
 * @param reminder 当前提醒时间，格式 "YYYY-MM-DDTHH:MM"，null 表示未设置
 * @param onClick 点击整行时触发的回调
 */
@Composable
private fun ReminderEntryRow(
    reminder: String?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Alarm 图标，已设置提醒时使用 secondary 色（信息性状态）
        Icon(
            LucideIcons.AlarmClock,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = if (reminder != null) MaterialTheme.colorScheme.secondary
                else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(8.dp))
        // "提醒" 标签
        Text(
            "提醒",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.weight(1f))
        // 当前值文字：未设置显示"设置提醒"，已设置显示格式化后的时间
        Text(
            text = reminder?.let { r ->
                try {
                    val ldt = java.time.LocalDateTime.parse(
                        r, java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
                    )
                    "%d月%d日 %02d:%02d".format(ldt.monthValue, ldt.dayOfMonth, ldt.hour, ldt.minute)
                } catch (_: Exception) { "已设置提醒" }
            } ?: "设置提醒",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            // 提醒时间文字使用 secondary（信息性状态）
            color = if (reminder != null) MaterialTheme.colorScheme.secondary
                else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 新建任务浮层的面板状态枚举：
 * - FORM：主表单（标题、描述、优先级、提醒/重复入口行）
 * - REMINDER：提醒设置面板（日期 + 时间选择）
 * - RECURRENCE：重复设置面板（不重复/每天 + 时间滚轮）
 */
private enum class AddPanel { FORM, REMINDER, RECURRENCE }

/**
 * 实体类型双段选择器 —— 「任务 / 笔记」（7.6 U-01）。
 *
 * 设计要求：图标与文字同时变化，不能只换颜色（颜色不是唯一状态信号）；
 * 高度 40dp，选中项用 secondaryContainer 填充，触摸区不小于 48dp。
 *
 * @param kind         当前类型："task" / "note"
 * @param onKindChange 用户切换类型时触发，回调新类型
 */
@Composable
private fun EntityKindSelector(
    kind: String,
    onKindChange: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        // 两个分段共用同一渲染逻辑，只有图标/文案/目标值不同
        listOf(
            Triple("task", "任务", LucideIcons.CircleCheck),
            Triple("note", "笔记", LucideIcons.FileText),
        ).forEach { (value, label, icon) ->
            val selected = kind == value
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onKindChange(value) },
                    ),
                shape = RoundedCornerShape(10.dp),
                // 选中态用 secondaryContainer，未选中保持容器底色（颜色过渡自然，不闪黑）
                color = if (selected) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surfaceContainerHighest,
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        icon,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * 预计时长入口行 —— 显示当前预计总工作量，点击打开快捷选择抽屉（7.9）。
 *
 * expected_minutes 是「预计总工作量」，不是强制计时长度，也不产生结束时间字段。
 *
 * @param minutes         当前预计分钟数，null = 未设置
 * @param onMinutesChange 选择/清除时回调；null 表示清除
 */
@Composable
private fun ExpectedMinutesRow(
    minutes: Long?,
    onMinutesChange: (Long?) -> Unit,
) {
    var showSheet by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { showSheet = true },
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            LucideIcons.Timer,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = if (minutes != null) MaterialTheme.colorScheme.secondary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            "预计时长",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = minutes?.let { "$it 分钟" } ?: "未设置",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (minutes != null) MaterialTheme.colorScheme.secondary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (showSheet) {
        ExpectedMinutesSheet(
            current = minutes,
            onConfirm = {
                onMinutesChange(it)
                showSheet = false
            },
            onDismiss = { showSheet = false },
        )
    }
}

/**
 * 预计时长选择抽屉 —— 15/25/45/60/90 快捷选项 + 自定义正整数 + 清除（7.9）。
 *
 * 校验：只接受正整数；0、负数、非数字都禁止保存（按钮置灰并给出提示）。
 *
 * @param current   当前值，null = 未设置
 * @param onConfirm 确认回调；传 null 表示清除
 * @param onDismiss 关闭回调
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExpectedMinutesSheet(
    current: Long?,
    onConfirm: (Long?) -> Unit,
    onDismiss: () -> Unit,
) {
    // 选中的值：null 表示「未设置」，与「清除」等价
    var selected by remember { mutableStateOf(current) }
    // 自定义输入框的文本；非空且合法时优先于快捷选项
    var customText by remember { mutableStateOf("") }
    val customValue = customText.trim().toLongOrNull()
    // 自定义输入非法（有输入但不是正整数）时禁止保存
    val customInvalid = customText.isNotBlank() && (customValue == null || customValue <= 0)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
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
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "这是预计总工作量，不会限制你的专注计时。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // 快捷选项：两行流式排布，触摸区高度 40dp + 外层间距补足 48dp
            androidx.compose.foundation.layout.FlowRow(
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
                        color = if (isSelected) MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Box(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "$preset 分钟",
                                style = MaterialTheme.typography.labelLarge,
                                color = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer
                                else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
            OutlinedTextField(
                value = customText,
                onValueChange = { input ->
                    // 只允许数字，避免用户输入字母后拿到莫名其妙的错误
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
            // 清除入口：与保存分开，避免误把「保存空值」当成清除
            TextButton(
                onClick = { onConfirm(null) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("清除") }
        }
    }
}

/**
 * 新建笔记浮层 —— 从 FAB 位置 morph 展开为居中的笔记创建表单。
 *
 * 仿 AddTaskOverlay 的视觉风格，但只有标题和正文两个输入框，没有优先级/提醒/循环。
 * 点"添加"后创建笔记并打开 Markdown 编辑器。
 *
 * @param onDismiss 点击外部区域关闭回调
 * @param onAdd 确认添加回调，传入 (标题, 正文Markdown)
 * @param sharedBoundsModifier shared element 动画 modifier（与 FAB 共享 bounds）
 */
@Composable
private fun AddNoteOverlay(
    onDismiss: () -> Unit,
    onAdd: (String, String?) -> Unit,
    sharedBoundsModifier: Modifier,
) {
    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }

    // 自动聚焦标题输入框
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    // 半透明背景 + 点击外部关闭
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = NionAlpha.OVERLAY_SCRIM))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = sharedBoundsModifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                // 标题
                Text(
                    "新建笔记",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                // 标题输入框：粗线边框，自动聚焦呼出键盘
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    placeholder = { Text("标题") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(3.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
                        .focusRequester(focusRequester),
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                    singleLine = true,
                )
                // 正文输入框：Markdown 内容
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    placeholder = { Text("开始写笔记...") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(3.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp)),
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                    maxLines = 5,
                )
                // 操作按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                    ) { Text("取消", fontWeight = FontWeight.SemiBold) }
                    Button(
                        onClick = {
                            if (title.isNotBlank()) onAdd(
                                title.trim(),
                                content.trim().ifBlank { null },
                            )
                        },
                        enabled = title.isNotBlank(),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier.weight(1f),
                    ) { Text("添加", fontWeight = FontWeight.SemiBold) }
                }
            }
        }
    }
}

/**
 * 新建实体浮层 —— 任务与笔记共用一个表单（7.6 单 FAB + 类型选择器）。
 *
 * 为什么合一：旧实现有 AddTaskOverlay 与 AddNoteOverlay 两套表单，用户在笔记型清单
 * 里建不了任务、在任务型清单里建不了笔记，且切换类型等于重开表单、草稿全丢。
 * 现在只有一个草稿与一个类型开关：
 * - 「任务 / 笔记」双段选择器默认值由清单语境给出（笔记型清单默认笔记）；
 * - 切换类型只隐藏/显示字段，不清空任何已输入内容；
 * - 笔记态展示标题 + Markdown 正文 + 附件；任务态额外展示优先级、提醒、重复、预计时长。
 *
 * @param onDismiss            关闭回调（已有输入时应由调用方确认是否放弃）
 * @param onAdd                提交回调，传入 (kind, 标题, 描述/正文, 优先级, 循环规则,
 *                             循环提醒时间, 一次性提醒, 预计时长)
 * @param sharedBoundsModifier shared element 动画 modifier（与 FAB 共享 bounds）
 * @param initialKind          打开时的默认类型（"task" / "note"）
 * @param pendingAttachments   当前暂存的附件列表
 * @param onPickImage          点击添加图片附件
 * @param onPickFile           点击添加文件附件
 * @param onRemoveAttachment   移除暂存附件
 * @param onPreviewImage       预览图片附件
 */
@Composable
private fun AddTaskOverlay(
    onDismiss: () -> Unit,
    /** 创建回调：kind, title, description, priority, recurrenceRule, recurrenceReminderTime, reminder, expectedMinutes */
    onAdd: (String, String, String?, String, String?, String?, String?, Long?) -> Unit,
    sharedBoundsModifier: Modifier,
    /** 打开时的默认类型：任务型清单/智能清单默认 task，笔记型清单默认 note */
    initialKind: String = "task",
    /** 当前暂存的附件列表 */
    pendingAttachments: List<AttachmentUiItem> = emptyList(),
    /** 点击添加图片附件时触发 */
    onPickImage: () -> Unit = {},
    /** 点击添加文件附件时触发 */
    onPickFile: () -> Unit = {},
    /** 移除暂存附件时触发 */
    onRemoveAttachment: (String) -> Unit = {},
    /** 预览图片附件时触发 */
    onPreviewImage: (AttachmentUiItem) -> Unit = {},
) {
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var priority by remember { mutableStateOf("medium") }
    // 循环规则：null 表示不循环，"daily" 表示每日循环
    var recurrenceRule by remember { mutableStateOf<String?>(null) }
    // 每日循环提醒时间：HH:MM 格式，默认 "09:00"
    var recurrenceReminderTime by remember { mutableStateOf<String?>(null) }
    // 一次性提醒时间：YYYY-MM-DDTHH:MM 格式，null 表示未设置
    var taskReminder by remember { mutableStateOf<String?>(null) }
    // 预计总工作量（分钟），null = 未规划
    var expectedMinutes by remember { mutableStateOf<Long?>(null) }
    // 当前面板状态，默认为 FORM 主表单
    var panel by remember { mutableStateOf(AddPanel.FORM) }
    // 当前实体类型：切换只改变可见字段，不重置上面任何草稿（7.6 明确要求）
    var kind by remember { mutableStateOf(if (initialKind == "note") "note" else "task") }
    val isNote = kind == "note"

    // 自动聚焦标题输入框，仅在首次展示 FORM 面板时呼出键盘
    // 从设置重复/提醒等子面板返回时不会重复聚焦
    val focusRequester = remember { FocusRequester() }
    var hasAutoFocused by remember { mutableStateOf(false) }
    LaunchedEffect(panel == AddPanel.FORM) {
        if (panel == AddPanel.FORM && !hasAutoFocused) {
            focusRequester.requestFocus()
            hasAutoFocused = true
        }
    }

    // 空标题或纯空白不视为有效标题（7.6）
    val canSubmit = title.isNotBlank()

    // 半透明背景 + 点击外部关闭
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = NionAlpha.OVERLAY_SCRIM))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        // 表单卡片：米白色背景
        Surface(
            modifier = sharedBoundsModifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            // AnimatedContent：三个面板之间左右滑动切换
            AnimatedContent(
                targetState = panel,
                transitionSpec = {
                    // 根据面板切换方向决定滑动方向
                    if (targetState == AddPanel.FORM) {
                        // 从子面板返回 FORM：子面板向右滑出，FORM 从左滑入
                        slideInHorizontally(
                            animationSpec = tween(350, easing = FastOutSlowInEasing),
                        ) { fullWidth -> -fullWidth } + fadeIn(tween(250)) togetherWith
                        slideOutHorizontally(
                            animationSpec = tween(350, easing = FastOutSlowInEasing),
                        ) { fullWidth -> fullWidth } + fadeOut(tween(200))
                    } else {
                        // 从 FORM 进入子面板：FORM 向左滑出，子面板从右滑入
                        slideInHorizontally(
                            animationSpec = tween(350, easing = FastOutSlowInEasing),
                        ) { fullWidth -> fullWidth } + fadeIn(tween(250)) togetherWith
                        slideOutHorizontally(
                            animationSpec = tween(350, easing = FastOutSlowInEasing),
                        ) { fullWidth -> -fullWidth } + fadeOut(tween(200))
                    } using SizeTransform(clip = false)
                },
                label = "addPanelTransition",
            ) { currentPanel ->
                when (currentPanel) {
                    AddPanel.FORM -> {
                        // ===== 主表单面板 =====
                        // 外层 verticalScroll：大字体/窄屏时表单可滚动，
                        // 保证提交按钮不会被 IME 或屏幕边缘裁掉
                        Column(
                            modifier = Modifier
                                .padding(20.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            // ── 类型双段选择器（U-01）：图标与文字同时变化，不只换色 ──
                            EntityKindSelector(kind = kind, onKindChange = { kind = it })
                            Text(
                                if (isNote) "新建笔记" else "新建任务",
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            // 标题输入框：粗线边框，自动聚焦；提示语随类型变化
                            OutlinedTextField(
                                value = title,
                                onValueChange = { title = it },
                                placeholder = { Text(if (isNote) "想记点什么？" else "要做点什么？") },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(3.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
                                    .focusRequester(focusRequester),
                                shape = RoundedCornerShape(14.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color.Transparent,
                                    unfocusedBorderColor = Color.Transparent,
                                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                ),
                                singleLine = true,
                            )
                            // 描述/正文输入框：右下角内嵌附件按钮，clip 防溢出
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp)),
                            ) {
                                OutlinedTextField(
                                    value = description,
                                    onValueChange = { description = it },
                                    placeholder = { Text(if (isNote) "正文（支持 Markdown）" else "描述") },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .border(3.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp)),
                                    shape = RoundedCornerShape(14.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = Color.Transparent,
                                        unfocusedBorderColor = Color.Transparent,
                                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    ),
                                    // 笔记正文是主体内容，给更高行数
                                    maxLines = if (isNote) 12 else 3,
                                )
                                // 附件按钮：定位在输入框右下角
                                AttachmentButton(
                                    onPickImage = onPickImage,
                                    onPickFile = onPickFile,
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(end = 4.dp, bottom = 4.dp),
                                )
                            }

                            // ── 任务专属字段：笔记态整体隐藏，但 state 保留（切回任务时恢复）──
                            if (!isNote) {
                                Text(
                                    "优先级",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                PrioritySelector(selected = priority, onSelect = { priority = it })

                                // 提醒入口行：显示当前提醒时间，点击切换到 REMINDER 面板
                                ReminderEntryRow(
                                    reminder = taskReminder,
                                    onClick = { panel = AddPanel.REMINDER },
                                )

                                // 重复入口行：显示当前循环设置，点击切换到 RECURRENCE 面板
                                RecurrenceEntryRow(
                                    recurrenceRule = recurrenceRule,
                                    reminderTime = recurrenceReminderTime,
                                    onClick = { panel = AddPanel.RECURRENCE },
                                )

                                // 预计时长：快捷选项 + 自定义 + 清除（7.9）
                                ExpectedMinutesRow(
                                    minutes = expectedMinutes,
                                    onMinutesChange = { expectedMinutes = it },
                                )
                            }

                            // 已暂存的附件缩略图列表（两种类型共用）
                            if (pendingAttachments.isNotEmpty()) {
                                AttachmentList(
                                    attachments = pendingAttachments,
                                    onRemove = onRemoveAttachment,
                                    onPreview = onPreviewImage,
                                )
                            }

                            // 操作按钮
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TextButton(
                                    onClick = onDismiss,
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(14.dp),
                                ) { Text("取消", fontWeight = FontWeight.SemiBold) }
                                Button(
                                    onClick = {
                                        if (canSubmit) onAdd(
                                            kind,
                                            title.trim(),
                                            description.trim().ifBlank { null },
                                            priority,
                                            recurrenceRule,
                                            recurrenceReminderTime,
                                            taskReminder,
                                            expectedMinutes,
                                        )
                                    },
                                    enabled = canSubmit,
                                    shape = RoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text(
                                        if (isNote) "添加笔记" else "添加任务",
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }
                        }
                    }

                    AddPanel.REMINDER -> {
                        // ===== 提醒设置面板 =====
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(20.dp),
                        ) {
                            // 面板标题栏：图标 + 标题 + 关闭按钮
                            PanelHeader(
                                icon = {
                                    Icon(
                                        LucideIcons.AlarmClock,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.secondary,
                                        modifier = Modifier.size(22.dp),
                                    )
                                },
                                title = "提醒设置",
                                onClose = { panel = AddPanel.FORM },
                            )

                            // 使用 ReminderTimePicker（startExpanded=true：直接显示日历，隐藏标题行）
                            // onReminderChanged 只管数据，onDismiss 负责返回 FORM 面板
                            ReminderTimePicker(
                                reminder = taskReminder,
                                onReminderChanged = { taskReminder = it },
                                startExpanded = true,
                                onDismiss = { panel = AddPanel.FORM },
                            )
                        }
                    }

                    AddPanel.RECURRENCE -> RecurrenceSettingPanel(
                        initialRule = recurrenceRule,
                        initialTime = recurrenceReminderTime,
                        onSave = { rule, time ->
                            recurrenceRule = rule
                            recurrenceReminderTime = time
                            panel = AddPanel.FORM
                        },
                        onCancel = { panel = AddPanel.FORM },
                    )
                }
            }
        } // end Surface
    } // end Box
}

/**
 * 面板标题栏 —— 图标 + 标题 + 关闭按钮，REMINDER 和 RECURRENCE 子面板共用。
 *
 * @param icon 面板图标，显示在左侧圆角方块内
 * @param title 面板标题文字
 * @param onClose 关闭按钮回调（返回 FORM 面板）
 */
@Composable
private fun PanelHeader(
    icon: @Composable () -> Unit,
    title: String,
    onClose: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            // 面板图标背景使用 tertiaryContainer
            color = MaterialTheme.colorScheme.tertiaryContainer,
            modifier = Modifier.size(40.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                icon()
            }
        }
        Spacer(modifier = Modifier.width(14.dp))
        Text(
            title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.weight(1f))
        IconButton(onClick = onClose) {
            Icon(LucideIcons.X, contentDescription = "关闭")
        }
    }
}

/**
 * 重复设置面板 —— 编辑每日循环规则和提醒时间。
 *
 * 内部持有本地编辑状态（localRule / localTime），
 * 确认后通过 onSave 回调写回外层，取消时丢弃本地修改。
 *
 * @param initialRule 初始循环规则（null=不循环，"daily"=每日）
 * @param initialTime 初始每日提醒时间 "HH:MM"
 * @param onSave 保存回调，传入 (规则, 时间)
 * @param onCancel 取消回调
 */
@Composable
private fun RecurrenceSettingPanel(
    initialRule: String?,
    initialTime: String?,
    onSave: (String?, String?) -> Unit,
    onCancel: () -> Unit,
) {
    // 本地编辑状态，确定后写回外层
    var localRule by remember { mutableStateOf(initialRule) }
    var localTime by remember { mutableStateOf(initialTime) }

    Column(
        modifier = Modifier.padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        // 面板标题栏
        PanelHeader(
            icon = {
                Icon(
                    LucideIcons.Repeat,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(22.dp),
                )
            },
            title = "重复设置",
            onClose = onCancel,
        )

        // 使用 RecurrenceSelector（不重复/每天 + 内联时间滚轮）
        RecurrenceSelector(
            recurrenceRule = localRule,
            reminderTime = localTime,
            onRecurrenceChanged = { localRule = it },
            onReminderTimeChanged = { localTime = it },
        )

        // 底部操作按钮：取消 + 保存
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = onCancel,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp),
            ) { Text("取消", fontWeight = FontWeight.SemiBold) }
            Button(
                onClick = { onSave(localRule, localTime) },
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                modifier = Modifier.weight(1f),
            ) { Text("保存", fontWeight = FontWeight.SemiBold) }
        }
    }
}

/**
 * 自适应高度布局 —— 头部固定、中间自适应、底部固定。
 *
 * 使用 SubcomposeLayout 分两轮测量：
 * 1. 第一轮：测量 header 和 footer 的实际高度
 * 2. 第二轮：根据剩余空间计算 body 的最大高度，再测量 body
 *
 * 整体高度跟随内容自适应，不超过 maxHeight。
 * 当内容总高度超过 maxHeight 时，body（备注输入框）被压缩并内部滚动，
 * header 和 footer 始终完整显示。
 *
 * @param maxHeight 布局的最大高度约束（通常是屏幕高度的 85%）
 * @param contentPadding 内容区域内边距
 * @param spacing 子元素之间的间距
 * @param header 头部内容（标题行），自然高度
 * @param body 中间自适应内容（备注输入框），接收可用的最大高度参数
 * @param footer 底部固定内容（专注信息 + 工具栏），自然高度
 */
@Composable
private fun AdaptiveHeightLayout(
    maxHeight: Dp,
    contentPadding: Dp = 20.dp,
    spacing: Dp = 16.dp,
    header: @Composable () -> Unit,
    body: @Composable (maxBodyHeight: Dp) -> Unit,
    footer: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val spacingPx = with(density) { spacing.roundToPx() }
    val paddingPx = with(density) { contentPadding.roundToPx() }
    // body 最小高度：备注输入框至少 120dp
    val minBodyPx = with(density) { 120.dp.roundToPx() }
    val maxHeightPx = with(density) { maxHeight.roundToPx() }

    SubcomposeLayout(
        modifier = Modifier.fillMaxWidth(),
    ) { constraints ->
        val width = constraints.maxWidth
        // 实际最大高度约束：取父约束和 maxHeight 中的较小值
        val effectiveMaxHeight = minOf(constraints.maxHeight, maxHeightPx)

        // 内容区域的约束（减去左右 padding）
        val contentConstraints = constraints.copy(
            minWidth = (width - paddingPx * 2).coerceAtLeast(0),
            maxWidth = (width - paddingPx * 2).coerceAtLeast(0),
        )

        // ===== 第一轮：测量头部和底部，获取实际高度 =====
        val headerPlaceable = subcompose("header", header).first()
            .measure(contentConstraints)
        val footerPlaceable = subcompose("footer", footer).first()
            .measure(contentConstraints)

        // ===== 计算中间区域的最大可用高度 =====
        // = 总最大高度 - 上下padding - 头部 - 底部 - 2个间距(header↔body, body↔footer)
        val maxBodyPx = (
            effectiveMaxHeight
                - paddingPx * 2
                - headerPlaceable.height
                - footerPlaceable.height
                - spacingPx * 2
        ).coerceAtLeast(minBodyPx)

        // ===== 第二轮：用计算出的最大高度测量中间内容 =====
        val bodyConstraints = contentConstraints.copy(
            minHeight = minBodyPx,
            maxHeight = maxBodyPx,
        )
        val bodyPlaceable = subcompose("body") {
            body(with(density) { maxBodyPx.toDp() })
        }.first().measure(bodyConstraints)

        // ===== 计算实际总高度并布局 =====
        val totalHeight = (
            paddingPx
                + headerPlaceable.height + spacingPx
                + bodyPlaceable.height + spacingPx
                + footerPlaceable.height
                + paddingPx
        ).coerceIn(0, effectiveMaxHeight)

        layout(width, totalHeight) {
            var y = paddingPx
            headerPlaceable.place(paddingPx, y)
            y += headerPlaceable.height + spacingPx
            bodyPlaceable.place(paddingPx, y)
            y += bodyPlaceable.height + spacingPx
            footerPlaceable.place(paddingPx, y)
        }
    }
}

/**
 * 空态视图 —— 细线图标 + 标题 + 辅助文案三行结构（7.4）。
 *
 * 不使用大面积插画；垂直居中并保留底部 FAB 的安全空间。
 * 文案由 ViewModel 按场景给出（纯任务 / 纯笔记 / 混合 / 今天 / 收集箱各不相同）。
 *
 * @param title       空态标题，如「还没有任务」
 * @param hint        辅助文案，可为空（为空时不渲染该行，不留空白）
 * @param actionHint  主操作提示，如「点击 + 添加第一个任务」
 * @param icon        细线图标，任务型用 CircleCheck，笔记型用 FileText
 */
@Composable
private fun EmptyTaskView(
    modifier: Modifier = Modifier,
    title: String = "还没有任务",
    hint: String = "",
    actionHint: String = "点击 + 添加第一个任务",
    icon: androidx.compose.ui.graphics.vector.ImageVector = LucideIcons.CircleCheck,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        // 底部留出 FAB 高度，避免提示文案被悬浮按钮压住
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(bottom = 72.dp),
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = NionAlpha.TEXT_SUBTITLE),
                modifier = Modifier.size(24.dp),
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (hint.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    hint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                actionHint,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = NionAlpha.TEXT_MEDIUM),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTopBar(
    selectedCount: Int,
    onClearSelection: () -> Unit,
    onDeleteSelected: () -> Unit,
) {
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onClearSelection) {
                Icon(LucideIcons.X, contentDescription = "取消选择")
            }
        },
        title = {
            Text(
                "已选择 $selectedCount 项",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        },
        actions = {
            IconButton(onClick = onDeleteSelected) {
                Icon(LucideIcons.Trash2, contentDescription = "删除选中", tint = MaterialTheme.colorScheme.error)
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    )
}

/**
 * 分组标签栏 —— 在选中清单后，显示该清单下的所有分组标签。
 * 水平滚动，支持切换分组筛选、新建分组、长按删除分组。
 *
 * @param groups 当前清单下的分组列表
 * @param activeGroupId 当前选中的分组 ID，null 表示"全部"
 * @param onGroupSelected 点击分组标签时触发，传入分组 ID 或 null
 * @param onCreateGroup 点击"+"按钮时触发，传入新分组名称
 * @param onDeleteGroup 长按分组标签时触发，传入要删除的分组 ID
 */
@Composable
private fun GroupTabBar(
    groups: List<GroupItem>,
    activeGroupId: String?,
    onGroupSelected: (String?) -> Unit,
    onCreateGroup: (String) -> Unit,
    onDeleteGroup: (String) -> Unit,
) {
    // 新建分组的输入状态：true=显示输入框，false=隐藏
    var showCreateInput by remember { mutableStateOf(false) }
    // 新建分组的名称文本
    var newGroupName by remember { mutableStateOf("") }
    // 输入框焦点控制
    val focusRequester = remember { FocusRequester() }
    // 长按触发的删除确认对话框
    var showDeleteDialogForId by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // "全部"标签 —— activeGroupId = null 时选中
            GroupTabChip(
                label = "全部",
                isSelected = activeGroupId == null,
                onClick = { onGroupSelected(null) },
                onLongClick = null,
            )
            // 各分组标签
            for (group in groups) {
                GroupTabChip(
                    label = group.name,
                    isSelected = activeGroupId == group.id,
                    onClick = { onGroupSelected(group.id) },
                    onLongClick = { showDeleteDialogForId = group.id },
                    color = group.color,
                )
            }
            // "+"按钮 —— 点击后展开输入框
            if (!showCreateInput) {
                IconButton(
                    onClick = { showCreateInput = true; newGroupName = "" },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        LucideIcons.CirclePlus,
                        contentDescription = "新建分组",
                        modifier = Modifier.size(20.dp),
                        // 次要操作使用 secondary
                        tint = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
        }

        // 新建分组输入框 —— 展开在标签栏下方
        if (showCreateInput) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = newGroupName,
                    onValueChange = { newGroupName = it },
                    placeholder = { Text("分组名称") },
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester),
                    textStyle = MaterialTheme.typography.bodyMedium,
                )
                TextButton(
                    onClick = {
                        if (newGroupName.isNotBlank()) {
                            onCreateGroup(newGroupName.trim())
                            newGroupName = ""
                            showCreateInput = false
                        }
                    },
                ) {
                    Text("确定")
                }
                TextButton(
                    onClick = { showCreateInput = false; newGroupName = "" },
                ) {
                    Text("取消")
                }
            }
            // 自动聚焦输入框
            LaunchedEffect(showCreateInput) {
                if (showCreateInput) {
                    focusRequester.requestFocus()
                }
            }
        }
    }

    // 删除分组确认对话框
    showDeleteDialogForId?.let { groupId ->
        val groupName = groups.find { it.id == groupId }?.name ?: ""
        AlertDialog(
            onDismissRequest = { showDeleteDialogForId = null },
            title = { Text("删除分组") },
            text = { Text("确定删除分组「$groupName」吗？组内任务不会被删除，将移至未分组。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteGroup(groupId)
                        showDeleteDialogForId = null
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialogForId = null }) {
                    Text("取消")
                }
            },
        )
    }
}

/**
 * 分组标签胶囊 —— 单个可点击的分组标签。
 *
 * @param label 显示文本
 * @param isSelected 是否选中（高亮样式）
 * @param onClick 点击回调
 * @param onLongClick 长按回调（用于触发删除），null 表示不支持长按
 * @param color 可选的颜色标识（如"#FF5722"）
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupTabChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    color: String? = null,
) {
    // 尝试解析分组颜色，失败则使用主题色
    val chipColor = remember(color) {
        try {
            if (color != null) android.graphics.Color.parseColor(color)
            else null
        } catch (_: Exception) { null }
    }
    val backgroundColor = if (isSelected) {
        chipColor?.let { Color(it) } ?: MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = if (isSelected) {
        chipColor?.let { c ->
            val r = android.graphics.Color.red(c)
            val g = android.graphics.Color.green(c)
            val b = android.graphics.Color.blue(c)
            if (r * 0.299 + g * 0.587 + b * 0.114 > 186) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onPrimary
        } ?: MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = Modifier
            .then(
                if (onLongClick != null) Modifier.combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                ) else Modifier.clickable(onClick = onClick)
            ),
        shape = RoundedCornerShape(20.dp),
        color = backgroundColor,
        contentColor = contentColor,
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}
