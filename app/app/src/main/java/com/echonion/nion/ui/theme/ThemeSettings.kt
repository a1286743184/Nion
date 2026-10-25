package com.echonion.nion.ui.theme

import uniffi.nion_core.NionCore

/**
 * 主题设置的读取工具 —— 从 settings 表还原当前生效的主题。
 *
 * 抽出来的原因：主界面（NionApp）与独立宿主（如全屏提醒页 ReminderFullscreenActivity）
 * 都要渲染 Nion 主题，但后者不经过 Compose 导航树，无法复用 NionApp 内部的私有函数。
 * 两处若各写一份，主题键名与回退规则很容易漂移，出现「提醒页与主界面配色不一致」。
 */
object ThemeSettings {

    /** settings 键：主题模式，值为 "preset" 或 "custom" */
    const val KEY_MODE = "theme_mode"
    /** settings 键：预设主题枚举名（如 "CORAL"） */
    const val KEY_PRESET = "color_theme"
    /** settings 键：当前生效的自定义主题 id */
    const val KEY_ACTIVE_CUSTOM = "active_custom_theme_id"
    /** settings 键：自定义主题列表 JSON */
    const val KEY_CUSTOM_LIST = "custom_themes_list"

    /**
     * 读取当前生效的主题色板。
     *
     * 读取顺序：
     * 1. `theme_mode` = "custom" → 按 `active_custom_theme_id` 在自定义列表里找；
     * 2. 其余情况（含缺失）→ 按 `color_theme` 找预设枚举；
     * 3. 任何一步失败都回退到 CORAL 预设，保证 UI 不会因主题读取失败而崩溃。
     *
     * @param core NionCore 单例
     * @return 当前主题色板
     */
    fun currentPalette(core: NionCore): ThemePalette {
        return try {
            val mode = core.getSetting(KEY_MODE) ?: "preset"
            if (mode == "custom") {
                val activeId = core.getSetting(KEY_ACTIVE_CUSTOM).orEmpty()
                if (activeId.isBlank()) return NionColorTheme.CORAL.palette()
                customThemes(core).firstOrNull { it.id == activeId }?.palette
                    ?: NionColorTheme.CORAL.palette()
            } else {
                val name = core.getSetting(KEY_PRESET) ?: "CORAL"
                (NionColorTheme.entries.find { it.name == name } ?: NionColorTheme.CORAL).palette()
            }
        } catch (_: Exception) {
            NionColorTheme.CORAL.palette()
        }
    }

    /**
     * 读取自定义主题列表；无配置或解析失败时返回空列表。
     *
     * @param core NionCore 单例
     */
    fun customThemes(core: NionCore): List<CustomThemeEntry> {
        return try {
            val json = core.getSetting(KEY_CUSTOM_LIST) ?: return emptyList()
            if (json.isBlank()) emptyList() else CustomThemeEntry.listFromJson(json)
        } catch (_: Exception) {
            emptyList()
        }
    }
}
