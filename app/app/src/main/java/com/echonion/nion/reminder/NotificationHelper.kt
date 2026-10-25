package com.echonion.nion.reminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.echonion.nion.MainActivity
import com.echonion.nion.R

/**
 * 通知工具类 —— 管理任务提醒的通知渠道和通知发送。
 *
 * 所有提醒通知使用同一个渠道 "task_reminders"，IMPORTANCE_HIGH 确保能发出声音和弹出横幅。
 * 每个任务使用 notificationId = taskId.hashCode()，保证同一任务只会有一个通知。
 *
 * 通知包含：
 * - 正文：Nion 的个性化提醒文案（LLM 生成或模板）
 * - 3 个 Action 按钮（根据紧迫度变化）：开始做了 / 等5分钟 / 今天算了
 * - 点击通知主体 → 打开 app 并展开伙伴面板
 */
object NotificationHelper {

    /** 通知渠道 ID，所有任务提醒共用此渠道 */
    private const val CHANNEL_ID = "task_reminders"
    /** 渠道名称，显示在系统设置中 */
    private const val CHANNEL_NAME = "任务提醒"
    /** 渠道描述 */
    private const val CHANNEL_DESC = "Nion 伙伴的个性化任务提醒"

    /** 天气预警通知渠道 ID */
    private const val WEATHER_CHANNEL_ID = "weather_alerts"
    /** 天气预警渠道名称 */
    private const val WEATHER_CHANNEL_NAME = "天气预警"
    /** 天气预警渠道描述 */
    private const val WEATHER_CHANNEL_DESC = "Nion 的天气变化提醒"

    // ── Intent extras（通知 → Activity 的约定键，避免各处硬编码字符串漂移）──
    /** 任务 ID */
    const val EXTRA_REMINDER_TASK_ID = "reminder_task_id"
    /** 任务标题（全屏页显示用） */
    const val EXTRA_REMINDER_TASK_TITLE = "reminder_task_title"
    /** 已清理 Markdown 的提醒文案（全屏页显示用） */
    const val EXTRA_REMINDER_MESSAGE = "reminder_message"
    /** 当前触发次数 */
    const val EXTRA_REMINDER_TRIGGER_COUNT = "reminder_trigger_count"
    /** 标记「点击通知主体打开实体详情」（区别于 open_companion 展开聊天面板） */
    const val EXTRA_OPEN_ENTITY = "open_entity"

    /**
     * 检查系统是否允许本应用使用全屏提醒（fullScreenIntent）。
     *
     * Android 14（API 34）起 `USE_FULL_SCREEN_INTENT` 变成需要用户授予的权限；
     * 未获允许时调用 setFullScreenIntent 只会在通知栏显示、不会唤亮全屏页。
     * 这里如实查询能力，供调用方决定「是否附加全屏 Intent」与「设置页如何提示」，
     * 不假设所有 ROM 都能全屏唤亮。
     *
     * @return true 表示可以请求全屏；低版本或无法判断时按可用处理
     */
    fun canUseFullScreenIntent(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        return try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.canUseFullScreenIntent()
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 系统通知总开关是否打开（用户可能只在系统设置里关掉本应用通知，
     * 而没有撤销 POST_NOTIFICATIONS 运行时权限）。
     */
    fun areNotificationsEnabled(context: Context): Boolean {
        return try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.areNotificationsEnabled()
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 打开本应用的通知设置页（通知权限被拒时的首选修复入口）。
     */
    fun openNotificationSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
            .onFailure { openAppDetailsSettings(context) }
    }

    /**
     * 打开本应用的全屏提醒权限设置页（Android 14+ 需要用户在系统里单独允许）。
     */
    fun openFullScreenIntentSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                data = android.net.Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (runCatching { context.startActivity(intent) }.isSuccess) return
        }
        // 低版本或跳转失败：退回应用详情页，用户仍可点进通知分类
        openAppDetailsSettings(context)
    }

    /** 打开本应用详情页（最后兜底） */
    private fun openAppDetailsSettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /** "正在输入"状态通知渠道 ID —— IMPORTANCE_MIN 确保无声无震动无横幅 */
    private const val TYPING_CHANNEL_ID = "typing_status"
    /** "正在输入"渠道名称 */
    private const val TYPING_CHANNEL_NAME = "输入状态"
    /** "正在输入"渠道描述 */
    private const val TYPING_CHANNEL_DESC = "Nion 正在生成回复时的状态提示"

    /** 定时手机自动化任务通知渠道 ID */
    private const val PHONE_AUTOMATION_CHANNEL_ID = "phone_automation"
    /** 定时手机自动化任务渠道名称 */
    private const val PHONE_AUTOMATION_CHANNEL_NAME = "定时手机任务"
    /** 定时手机自动化任务渠道描述 */
    private const val PHONE_AUTOMATION_CHANNEL_DESC = "Nion 定时自动执行的手机任务通知"

    /**
     * 创建通知渠道。
     * 必须在 Application.onCreate() 中调用，且只在 Android 8.0+ 需要创建。
     * 重复调用是安全的（系统会忽略已存在的渠道）。
     */
    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = CHANNEL_DESC
                enableVibration(true)
                setShowBadge(true)
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)

            // 天气预警渠道（默认优先级，不紧急）
            val weatherChannel = NotificationChannel(
                WEATHER_CHANNEL_ID,
                WEATHER_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = WEATHER_CHANNEL_DESC
                enableVibration(true)
                setShowBadge(true)
            }
            manager.createNotificationChannel(weatherChannel)

            // "正在输入"状态渠道（最低优先级，不会弹横幅、响铃或震动）
            val typingChannel = NotificationChannel(
                TYPING_CHANNEL_ID,
                TYPING_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_MIN,
            ).apply {
                description = TYPING_CHANNEL_DESC
                enableVibration(false)
                setShowBadge(false)
                setSound(null, null)
            }
            manager.createNotificationChannel(typingChannel)

            // 定时手机自动化任务渠道（高优先级，确保用户看到执行通知）
            val phoneAutoChannel = NotificationChannel(
                PHONE_AUTOMATION_CHANNEL_ID,
                PHONE_AUTOMATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = PHONE_AUTOMATION_CHANNEL_DESC
                enableVibration(true)
                setShowBadge(true)
            }
            manager.createNotificationChannel(phoneAutoChannel)
        }
    }

    /**
     * 显示任务提醒通知（带 Action 按钮）。
     *
     * 根据 triggerCount 调整按钮文案，实现渐进式递进效果：
     * - 按钮1 固定为「知道了」：点击标记任务完成（区分每日/普通任务）+ 终止提醒循环
     * - triggerCount 1-2：知道了 / 等5分钟
     * - triggerCount 3：知道了 / 最后5分钟
     * - triggerCount 4：知道了 / 真的不做了
     * - triggerCount 5：知道了（仅一个按钮）
     *
     * 高优任务在系统允许时附加 fullScreenIntent（息屏/锁屏直接唤亮全屏提醒页），
     * 普通任务只发 HIGH 通知不申请全屏打断；全屏能力被拒绝时自动退化为普通 HIGH 通知。
     *
     * @param context 上下文
     * @param taskId 任务 ID，同时用作通知 ID（通过 hashCode）
     * @param taskTitle 任务标题，用于 Action 按钮 Intent 传递
     * @param message Nion 的提醒文案（LLM 生成或模板），系统通知不支持 Markdown，会自动清理
     * @param triggerCount 当前触发次数（1-5），决定按钮文案
     * @param taskPriority 任务优先级，仅 "high" 才考虑全屏提醒（7.13 定稿）
     * @param allowFullScreenIntent 系统是否授予全屏提醒能力；false 时降级为 HIGH 通知
     */
    fun showReminderNotification(
        context: Context,
        taskId: String,
        taskTitle: String,
        message: String,
        triggerCount: Int,
        taskPriority: String = "medium",
        allowFullScreenIntent: Boolean = false,
    ) {
        // 系统通知不支持 Markdown 渲染，清理后显示纯文本
        val plainMessage = ReminderUtils.stripMarkdown(message)
        val notificationId = taskId.hashCode() and 0x7FFFFFFF
        val labels = ReminderMessageGenerator.getActionLabels(triggerCount)

        // 点击通知主体 → 打开该任务详情（不是无条件展开伙伴面板）。
        // 走 openEntity 语义：MainActivity 收到后只消费一次，冷启动与 onNewIntent 同一路径。
        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_REMINDER_TASK_ID, taskId)
            // 标记为「打开实体详情」而非展开聊天面板
            putExtra(EXTRA_OPEN_ENTITY, true)
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // 全屏提醒 Intent：独立宿主 Activity，不复用聊天页。
        // 只有高优任务且系统授予能力时才设置，否则该字段为 null，通知退化为普通 HIGH。
        val fullScreenPendingIntent = if (taskPriority == "high" && allowFullScreenIntent) {
            Intent(context, ReminderFullscreenActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_REMINDER_TASK_ID, taskId)
                putExtra(EXTRA_REMINDER_TASK_TITLE, taskTitle)
                putExtra(EXTRA_REMINDER_MESSAGE, plainMessage)
                putExtra(EXTRA_REMINDER_TRIGGER_COUNT, triggerCount)
            }.let {
                PendingIntent.getActivity(
                    context,
                    notificationId + 100,
                    it,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            }
        } else null

        // 收拢状态：标题用任务名，正文极短，确保按钮在收拢通知栏中直接可见。
        // 展开后：显示 Nion 的完整提醒文案（BigTextStyle.bigText）。
        // 正文口径按 7.13：首次「该做啦 · 现在」，重复「第 N 次提醒」。
        val collapsedText = if (triggerCount == 1) "该做啦 · 现在" else "第${triggerCount}次提醒"
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(taskTitle)
            .setContentText(collapsedText)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .setBigContentTitle(taskTitle)
                    .bigText(plainMessage)
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(contentPendingIntent)
            // 分类与可见性：提醒类通知，锁屏是否展示详情仍遵循用户系统设置
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            // 高优任务的系统提醒通道需求：即使被降级为普通通知也保持 heads-up 行为
            .setDefaults(NotificationCompat.DEFAULT_ALL)

        if (fullScreenPendingIntent != null) {
            builder.setFullScreenIntent(fullScreenPendingIntent, true)
        }

        // 按钮1：「知道了」—— 标记任务完成（区分每日/普通任务）+ 终止提醒循环 + 取消通知
        if (labels.first.isNotEmpty()) {
            val completeIntent = Intent(context, ReminderActionReceiver::class.java).apply {
                action = ReminderActionReceiver.ACTION_COMPLETE
                putExtra(ReminderActionReceiver.EXTRA_TASK_ID, taskId)
            }
            val completePendingIntent = PendingIntent.getBroadcast(
                context,
                notificationId + 1,
                completeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(0, labels.first, completePendingIntent)
        }

        // 按钮2：「等5分钟」（或「最后5分钟」「真的不做了」）
        if (labels.second.isNotEmpty()) {
            // triggerCount >= 4 时，第二个按钮变为「真的不做了」(DISMISS)
            val snoozeAction = if (triggerCount >= 4) {
                ReminderActionReceiver.ACTION_DISMISS
            } else {
                ReminderActionReceiver.ACTION_SNOOZE
            }
            val snoozeIntent = Intent(context, ReminderActionReceiver::class.java).apply {
                action = snoozeAction
                putExtra(ReminderActionReceiver.EXTRA_TASK_ID, taskId)
            }
            val snoozePendingIntent = PendingIntent.getBroadcast(
                context,
                notificationId + 2,
                snoozeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(0, labels.second, snoozePendingIntent)
        }

        // 按钮3：「今天算了」（仅 triggerCount < 4 时显示）
        if (labels.third.isNotEmpty()) {
            val dismissIntent = Intent(context, ReminderActionReceiver::class.java).apply {
                action = ReminderActionReceiver.ACTION_DISMISS
                putExtra(ReminderActionReceiver.EXTRA_TASK_ID, taskId)
            }
            val dismissPendingIntent = PendingIntent.getBroadcast(
                context,
                notificationId + 3,
                dismissIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(0, labels.third, dismissPendingIntent)
        }

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(notificationId, builder.build())
    }

    /**
     * 显示简易通知（无 Action 按钮，用于最终告别等场景）。
     *
     * @param context 上下文
     * @param taskId 任务 ID
     * @param message 通知文案
     */
    fun showSimpleNotification(context: Context, taskId: String, message: String) {
        val plainMessage = ReminderUtils.stripMarkdown(message)
        val notificationId = taskId.hashCode() and 0x7FFFFFFF

        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Nion")
            .setContentText(plainMessage)
            .setStyle(NotificationCompat.BigTextStyle().bigText(plainMessage))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(notificationId, notification)
    }

    /**
     * 显示问候通知（无 Action 按钮，点击打开伙伴面板）。
     *
     * 用于早安/午间/晚间问候场景，通知优先级为 DEFAULT（不紧急）。
     *
     * @param context 上下文
     * @param type 问候类型（"morning"/"noon"/"evening"），用于生成通知 ID 和标题
     * @param message 问候文案（LLM 生成或模板）
     */
    fun showGreetingNotification(context: Context, type: String, message: String) {
        val plainMessage = ReminderUtils.stripMarkdown(message)
        val notificationId = ("greeting_$type").hashCode() and 0x7FFFFFFF

        // 点击通知 → 打开 app 并展开伙伴面板
        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_companion", true)
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // 根据问候类型设置标题
        val title = when (type) {
            "morning" -> "早安问候"
            "noon" -> "午间检查"
            "evening" -> "晚间总结"
            else -> "Nion"
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(plainMessage)
            .setStyle(NotificationCompat.BigTextStyle().bigText(plainMessage))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(contentPendingIntent)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(notificationId, notification)
    }

    /**
     * 显示天气预警通知（无 Action 按钮，点击打开伙伴面板）。
     *
     * 用于天气变化预警场景（降雨、降温、大风等），通知优先级根据严重程度调整。
     *
     * @param context 上下文
     * @param message 预警文案（LLM 生成或模板）
     * @param severity 严重程度：info/warning/urgent
     */
    fun showWeatherAlertNotification(context: Context, message: String, severity: String) {
        val plainMessage = ReminderUtils.stripMarkdown(message)
        val notificationId = "weather_alert".hashCode() and 0x7FFFFFFF

        // 点击通知 → 打开 app 并展开伙伴面板
        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_companion", true)
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val title = when (severity) {
            "urgent" -> "天气紧急提醒"
            "warning" -> "天气提醒"
            else -> "天气提示"
        }

        val priority = when (severity) {
            "urgent" -> NotificationCompat.PRIORITY_HIGH
            "warning" -> NotificationCompat.PRIORITY_DEFAULT
            else -> NotificationCompat.PRIORITY_LOW
        }

        val notification = NotificationCompat.Builder(context, WEATHER_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(plainMessage)
            .setStyle(NotificationCompat.BigTextStyle().bigText(plainMessage))
            .setPriority(priority)
            .setAutoCancel(true)
            .setContentIntent(contentPendingIntent)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(notificationId, notification)
    }

    /**
     * 取消指定任务的通知。
     * 当用户在应用内处理了提醒后调用，避免通知栏还残留。
     */
    fun dismissNotification(context: Context, taskId: String) {
        val notificationId = taskId.hashCode() and 0x7FFFFFFF
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(notificationId)
    }

    /**
     * 取消指定类型的问候通知。
     * 当悬浮窗或 App 内 Overlay 接管后调用，避免通知栏残留。
     *
     * @param context Android 上下文，用于获取 NotificationManager
     * @param type 问候类型："morning" / "noon" / "evening"
     */
    fun dismissGreetingNotification(context: Context, type: String) {
        val notificationId = ("greeting_$type").hashCode() and 0x7FFFFFFF
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(notificationId)
    }

    /**
     * 取消天气预警通知。
     * 当悬浮窗或 App 内 Overlay 接管后调用，避免通知栏残留。
     */
    fun dismissWeatherAlertNotification(context: Context) {
        val notificationId = "weather_alert".hashCode() and 0x7FFFFFFF
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(notificationId)
    }

    /**
     * 显示"正在输入..."状态通知。
     *
     * 在 Worker 开始 LLM 生成前调用，用最低优先级通知安静地出现在通知栏，
     * 告知用户 Nion 正在准备回复。文案格式："{companionName} 正在输入..."
     *
     * @param context 上下文
     * @param type 场景标识（如 "greeting_morning" / "weather_alert" / "reminder_{taskId}"），用于生成唯一通知 ID
     * @param companionName 用户设置的伙伴名称
     */
    fun showTypingNotification(context: Context, type: String, companionName: String) {
        val notificationId = ("typing_$type").hashCode() and 0x7FFFFFFF
        val notification = NotificationCompat.Builder(context, TYPING_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(companionName)
            .setContentText("正在输入...")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSilent(true)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(notificationId, notification)
    }

    /**
     * 取消"正在输入..."状态通知。
     *
     * 在 LLM 生成完毕、正式通知/悬浮窗弹出后调用，移除"正在输入"提示。
     *
     * @param context 上下文
     * @param type 场景标识，必须与 showTypingNotification 传入的一致
     */
    fun dismissTypingNotification(context: Context, type: String) {
        val notificationId = ("typing_$type").hashCode() and 0x7FFFFFFF
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(notificationId)
    }
}
