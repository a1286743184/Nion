package com.echonion.nion.reminder

import android.app.Application
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.echonion.nion.NionApp
import com.echonion.nion.ui.companion.MarkdownText
import com.echonion.nion.ui.icons.LucideIcons
import com.echonion.nion.ui.theme.NionTheme
import com.echonion.nion.ui.theme.ThemeSettings
import uniffi.nion_core.TaskData

/**
 * 高优提醒全屏页 —— fullScreenIntent 的宿主，独立于聊天页与主界面。
 *
 * 为什么独立：通知的 fullScreenIntent 会在锁屏/息屏时直接唤亮一个 Activity，
 * 用聊天页当宿主的后果是用户被丢进一个上下文无关的对话里，还得自己找任务。
 * 这里只承载「看到这条提醒 + 三个明确动作」，动作语义与通知按钮完全一致。
 *
 * 底线行为（7.13）：
 * - 系统返回/手势关闭只代表关掉这一屏，**不取消系统通知** —— 通知继续兜底；
 * - 屏幕唤醒与锁屏展示遵循系统能力，不做循环动画、闪烁或强制振动；
 * - 设备不授予全屏能力时 NotificationHelper 根本不会带上 fullScreenIntent，
 *   本页自然不会出现，提醒退化为 HIGH 通知，Worker 不受影响。
 */
class ReminderFullscreenActivity : ComponentActivity() {

    /** 来自通知 Intent 的任务 ID；为空时立即结束，避免空白页 */
    private var taskId: String = ""
    private var taskTitle: String = ""
    private var message: String = ""
    private var triggerCount: Int = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        taskId = intent?.getStringExtra(NotificationHelper.EXTRA_REMINDER_TASK_ID).orEmpty()
        taskTitle = intent?.getStringExtra(NotificationHelper.EXTRA_REMINDER_TASK_TITLE).orEmpty()
        message = intent?.getStringExtra(NotificationHelper.EXTRA_REMINDER_MESSAGE).orEmpty()
        triggerCount = intent?.getIntExtra(NotificationHelper.EXTRA_REMINDER_TRIGGER_COUNT, 1) ?: 1

        if (taskId.isEmpty()) {
            Log.w(TAG, "缺少 taskId，直接结束全屏提醒页")
            finish()
            return
        }

        // 息屏状态下唤亮屏幕：仅在系统授予全屏能力、且锁屏未加密时生效，
        // 失败不影响页面本身（系统会把页面压到锁屏之上或退化为通知）
        applyShowWhenLocked()

        enableEdgeToEdge()

        val app = applicationContext as Application
        val palette = try {
            ThemeSettings.currentPalette((app as NionApp).core)
        } catch (_: Exception) {
            com.echonion.nion.ui.theme.NionColorTheme.CORAL.palette()
        }

        setContent {
            NionTheme(palette = palette) {
                ReminderFullscreenContent(
                    taskTitle = taskTitle,
                    message = message,
                    triggerCount = triggerCount,
                    expectedMinutes = loadExpectedMinutes(app, taskId),
                    onStartFocus = { handleStartFocus(app) },
                    onSnooze = { handleSnooze(app) },
                    onDismissForToday = { handleDismissForToday(app) },
                )
            }
        }
    }

    /**
     * 让 Activity 在锁屏上显示并尝试点亮屏幕。
     *
     * Android 8.1+ 使用 Activity.setShowWhenLocked / setTurnScreenOn；
     * 更低版本退回到 Window flag。两者都用 runCatching 保护，
     * 因为部分 ROM 会对这些调用抛 SecurityException。
     */
    private fun applyShowWhenLocked() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(true)
                setTurnScreenOn(true)
            } else {
                @Suppress("DEPRECATION")
                window.addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
                )
            }
        }.onFailure { Log.w(TAG, "设置锁屏显示/唤亮失败（不影响页面）", it) }
    }

    /**
     * 读取任务的预计时长（分钟），用于页面上显示「预计 45 分钟」。
     * 任务被删除或读取失败时返回 null，页面只是少一行元信息。
     */
    private fun loadExpectedMinutes(app: Application, taskId: String): Long? {
        return runCatching { (app as NionApp).core.getTask(taskId).expectedMinutes }.getOrNull()
    }

    /**
     * 主按钮「开始专注」：终止提醒循环并跳到专注页（不自动开始计时）。
     *
     * 与通知 Action「开始做了」的语义一致：终止循环、取消通知、带任务信息跳转。
     */
    private fun handleStartFocus(app: Application) {
        ReminderStore.resetTriggerCount(this, taskId)
        ReminderScheduler.cancelReminder(this, taskId)
        NotificationHelper.dismissNotification(this, taskId)

        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent != null) {
            launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            launchIntent.putExtra("reminder_action", "start_focus")
            launchIntent.putExtra(NotificationHelper.EXTRA_REMINDER_TASK_ID, taskId)
            launchIntent.putExtra("focus_task_title", taskTitle)
            launchIntent.putExtra("auto_start_focus", false)
            startActivity(launchIntent)
        }
        finish()
    }

    /**
     * 次按钮「稍后 5 分钟」：取消当前通知并调度 5 分钟后的新提醒。
     * 与通知 Action「等 5 分钟」一致（不重置 trigger_count，循环继续递进）。
     */
    private fun handleSnooze(app: Application) {
        NotificationHelper.dismissNotification(this, taskId)
        ReminderScheduler.scheduleSnoozeReminder(this, taskId, SNOOZE_MINUTES)
        finish()
    }

    /**
     * 文字按钮「今天算了」：终止提醒循环并取消通知。
     * 只关闭提醒，不标记任务完成 —— 与通知 Action「今天算了」一致。
     */
    private fun handleDismissForToday(app: Application) {
        ReminderStore.resetTriggerCount(this, taskId)
        ReminderScheduler.cancelReminder(this, taskId)
        NotificationHelper.dismissNotification(this, taskId)
        finish()
    }

    companion object {
        private const val TAG = "ReminderFullscreen"
        /** 「稍后提醒」的延迟分钟数，与通知 Action 的文案保持一致 */
        private const val SNOOZE_MINUTES = 5
    }
}

/**
 * 全屏提醒页内容 —— 顶部应用标识、中部任务信息、底部固定三动作。
 *
 * 布局遵循 7.13：背景用主题 background，任务名最多 3 行，伙伴文案最多 6 行，
 * 正文放在唯一滚动容器里，避免长文案把底部按钮顶出屏幕。
 *
 * @param taskTitle        任务名
 * @param message          伙伴生成的提醒文案（已清理 Markdown 标记，此处再按 Markdown 渲染一次以保留表情包）
 * @param triggerCount     当前触发次数，>1 时显示「第 N 次提醒」
 * @param expectedMinutes  预计时长（分钟），null 时不显示
 * @param onStartFocus     点击「开始专注」
 * @param onSnooze         点击「稍后 5 分钟」
 * @param onDismissForToday 点击「今天算了」
 */
@Composable
private fun ReminderFullscreenContent(
    taskTitle: String,
    message: String,
    triggerCount: Int,
    expectedMinutes: Long?,
    onStartFocus: () -> Unit,
    onSnooze: () -> Unit,
    onDismissForToday: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // ── 顶部标识：应用名 + 闹钟图标 ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 28.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(36.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            LucideIcons.AlarmClock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.size(10.dp))
                Text(
                    "Nion · 任务提醒",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ── 中部：任务名 + 元信息 + 提醒文案（唯一滚动容器）──
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    taskTitle,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(8.dp))
                // 元信息行：第 N 次提醒 / 预计时长。两者都没有时不占位。
                val metaParts = buildList {
                    if (triggerCount > 1) add("第 $triggerCount 次提醒")
                    if (expectedMinutes != null) add("预计 $expectedMinutes 分钟")
                }
                if (metaParts.isNotEmpty()) {
                    Text(
                        metaParts.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (message.isNotBlank()) {
                    Spacer(modifier = Modifier.height(20.dp))
                    MarkdownText(
                        content = message,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }
            }

            // ── 底部：固定三动作，顺序与通知按钮一致 ──
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Button(
                    onClick = onStartFocus,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    Text("开始专注", fontWeight = FontWeight.SemiBold)
                }
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = onSnooze,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                ) {
                    Text("稍后 5 分钟")
                }
                Spacer(modifier = Modifier.height(4.dp))
                TextButton(onClick = onDismissForToday) {
                    Text(
                        "今天算了",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
