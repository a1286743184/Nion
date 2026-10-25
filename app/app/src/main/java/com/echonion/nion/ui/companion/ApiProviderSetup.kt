package com.echonion.nion.ui.companion

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.echonion.nion.ui.icons.LucideIcons

/**
 * API 配置引导页 —— 当用户尚未配置 API key 时显示，也可用于编辑已保存的配置。
 *
 * 配置流程：
 * 1. 从预置列表中选择 Provider（OpenAI / Anthropic / DeepSeek / 自定义）
 * 2. 输入 API Key，点击"获取模型列表"从 API 拉取可用模型
 * 3. 从下拉列表中选择模型（自定义 provider 可手动输入）
 * 4. 点击保存按钮保存配置
 *
 * @param companionName 伙伴名称，显示在页面标题中
 * @param initialConfig 可选的初始配置，传入时进入编辑模式，预填充所有字段
 * @param onSave 保存按钮回调，传入完整配置信息（含用户选中的模型名）；
 *               编辑模式下额外传入原配置的 id
 */
@Composable
fun ApiProviderSetup(
    companionName: String = "Nion",
    initialConfig: SavedConfig? = null,
    viewModel: CompanionViewModel? = null,
    onSave: (provider: ProviderConfig, apiKey: String, model: String, baseUrl: String, effort: String?) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()

    val isEditMode = initialConfig != null

    // 根据 initialConfig 查找对应的内置 provider 索引，未匹配则回退到 0
    val initialProviderIndex = if (initialConfig != null) {
        builtInProviders.indexOfFirst { it.name == initialConfig.provider }.takeIf { it >= 0 } ?: 0
    } else 0

    // provider 下拉菜单展开状态
    var dropdownExpanded by remember { mutableStateOf(false) }
    // 当前选中的 provider 索引，编辑模式下初始化为原配置的 provider
    var selectedProviderIndex by remember { mutableStateOf(initialProviderIndex) }
    // API key 输入值，编辑模式下预填充
    var apiKeyInput by remember { mutableStateOf(initialConfig?.apiKey ?: "") }
    // API key 是否明文显示
    var apiKeyVisible by remember { mutableStateOf(false) }

    // 自定义 baseUrl（仅"自定义"provider 时使用），编辑模式下预填充
    var customBaseUrl by remember { mutableStateOf(initialConfig?.baseUrl ?: "") }

    // 模型获取相关状态
    var isFetchingModels by remember { mutableStateOf(false) }
    // 从 API 获取到的模型目录（含 effort 档位、多模态等上游元数据）。
    // 初值取 ViewModel 里最近一次拉取的共享目录：用户之前拉过的话，一进页面
    // 就能立刻看到模型列表与思考强度档位，不必再点一次刷新。
    var availableModels by remember {
        mutableStateOf(viewModel?.modelCatalog.orEmpty())
    }
    // 模型获取失败时的错误信息
    var fetchError by remember { mutableStateOf<String?>(null) }
    // 模型下拉菜单展开状态（从获取到的列表中选取）
    var modelDropdownExpanded by remember { mutableStateOf(false) }
    // 用户选中的模型名（从获取列表中选择，或自定义时手动输入），编辑模式下预填充
    var selectedModelName by remember { mutableStateOf(initialConfig?.model ?: "") }
    // 自定义 provider 时手动输入模型名，编辑模式下预填充
    var customModelInput by remember { mutableStateOf(initialConfig?.model ?: "") }
    // 用户选中的思考强度档位；null/"默认" = 不发送 effort 参数（跟随上游默认）。
    // 编辑模式下预填充原配置的档位。
    var selectedEffort by remember { mutableStateOf(initialConfig?.reasoningEffort) }

    // 当前选中模型的元数据（决定 effort 档位选项列表和能力标签展示）
    val selectedModelMeta = availableModels.find { it.id == selectedModelName }

    val selectedProvider = builtInProviders[selectedProviderIndex]
    val isCustom = selectedProvider.name == "自定义"

    // 是否已获取到模型列表
    val hasModels = availableModels.isNotEmpty()

    /**
     * 调用 ChatService.fetchModelCatalog 获取模型目录（含能力元数据）。
     *
     * 自定义 Provider 同样允许拉取：中转站/聚合网关基本都兼容 GET /models，
     * 拉取失败不阻塞——错误显示后用户仍可手动输入模型名。
     * 结果同时写入 ViewModel 的共享目录（供发图前校验等全局使用）。
     */
    fun fetchModels() {
        if (isCustom) {
            // 自定义 provider：baseUrl 必须已填写才能拉取
            if (customBaseUrl.trim().isEmpty() || isFetchingModels) return
        }
        val key = apiKeyInput.trim()
        if (key.isEmpty() || isFetchingModels) return
        val effectiveProvider = if (isCustom) {
            val base = customBaseUrl.trim().let {
                if (it.isNotEmpty() && !it.startsWith("http://") && !it.startsWith("https://")) "https://$it" else it
            }
            selectedProvider.copy(baseUrl = base)
        } else selectedProvider
        isFetchingModels = true
        fetchError = null
        scope.launch {
            val result = ChatService.fetchModelCatalog(effectiveProvider, key)
            result.onSuccess { models ->
                availableModels = models
                // 同步到 ViewModel 共享目录（发图前校验用）；viewModel 为 null 时静默跳过
                viewModel?.updateModelCatalog(models)
            }.onFailure { e ->
                fetchError = e.message ?: "获取模型失败"
            }
            isFetchingModels = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // 进入页面时自动拉取一次模型目录，让用户一打开就能看到模型能力标签与
        // 思考强度档位，不必先猜到要手动点按钮。
        // 触发条件：已有 API key（编辑模式/刚输完）、目录为空、自定义时 baseUrl 已填。
        // 仅首次组合执行；失败不在此弹错（避免网络差时进页面一片红），
        // 错误由用户手动点拉取时展示。
        LaunchedEffect(Unit) {
            if (apiKeyInput.isNotBlank() && availableModels.isEmpty() &&
                (!isCustom || customBaseUrl.isNotBlank())) {
                fetchModels()
            }
        }
        // 标题区：编辑模式显示"编辑配置"，新增模式显示欢迎语
        Text(
            if (isEditMode) "编辑配置" else "欢迎使用 $companionName",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            if (isEditMode) "修改 Provider、API Key 或模型后保存即可生效。"
            else "选择一个 AI Provider 并输入 API Key，然后获取可用模型即可开始对话。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(4.dp))

        // ---- 第 1 步：选择 Provider ----
        Text(
            "1. 选择 Provider",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ProviderSelectorCard(
            selectedProviderIndex = selectedProviderIndex,
            dropdownExpanded = dropdownExpanded,
            // 切换 provider 时重置模型列表及相关状态
            onProviderSelected = { index ->
                selectedProviderIndex = index
                dropdownExpanded = false
                availableModels = emptyList()
                selectedModelName = ""
                selectedEffort = null
                fetchError = null
            },
            onDropdownToggle = { dropdownExpanded = !dropdownExpanded },
        )

        // 自定义 provider：baseUrl 变化时清空旧的拉取结果（旧列表基于旧地址）
        if (isCustom) {
            OutlinedTextField(
                value = customBaseUrl,
                onValueChange = {
                    customBaseUrl = it
                    if (availableModels.isNotEmpty()) {
                        availableModels = emptyList()
                        selectedModelName = ""
                        selectedEffort = null
                        fetchError = null
                    }
                },
                label = { Text("API Base URL") },
                placeholder = { Text("https://api.example.com/v1") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next,
                ),
                shape = RoundedCornerShape(12.dp),
            )
        }

        // ---- 第 2 步：输入 API Key ----
        Text(
            "2. 输入 API Key",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ApiKeyInputField(
            apiKey = apiKeyInput,
            // API key 变动时重置模型列表（安全起见，旧 key 获取的列表可能无效）
            onApiKeyChange = {
                apiKeyInput = it
                if (availableModels.isNotEmpty()) {
                    availableModels = emptyList()
                    selectedModelName = ""
                    selectedEffort = null
                    fetchError = null
                }
            },
            apiKeyVisible = apiKeyVisible,
            onToggleVisibility = { apiKeyVisible = !apiKeyVisible },
            focusManager = focusManager,
        )

        // ---- 第 3 步：获取并选择模型 ----
        ModelSectionHeader(
            isCustom = isCustom,
            isFetchingModels = isFetchingModels,
            apiKeyInput = apiKeyInput,
            baseUrlInput = customBaseUrl,
            onFetchModels = { fetchModels() },
        )

        // 获取失败时显示错误信息
        if (fetchError != null) {
            Text(
                fetchError!!,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 4.dp),
            )
        }

        // 模型选择器 —— 两种 Provider 共用同一套卡片（拉取到的目录含能力元数据）。
        // 无数据时给出明确提示，避免用户面对一片空白不知道下一步做什么。
        if (hasModels) {
            ModelSelectorCard(
                models = availableModels,
                selectedModelName = selectedModelName,
                onModelSelected = { model ->
                    selectedModelName = model
                    modelDropdownExpanded = false
                    // 切换模型时按上游目录重置思考强度：
                    // 预选上游默认档位；上游未声明档位时置 null（不发送参数）
                    val meta = availableModels.find { it.id == model }
                    selectedEffort = if (meta?.hasEfforts == true) {
                        meta.defaultEffort?.takeIf { it in meta.supportedEfforts }
                            ?: meta.supportedEfforts.firstOrNull()
                    } else null
                },
                dropdownExpanded = modelDropdownExpanded,
                onDropdownToggle = { modelDropdownExpanded = !modelDropdownExpanded },
            )
        } else {
            // 尚未拉取：提示用户点右上角按钮。拉取后这里会变成可展开的模型列表，
            // 每个条目下方显示该模型的能力标签（可看图 / 思考档位 / 上下文长度）。
            Text(
                if (fetchError != null) {
                    "拉取失败，可在下方手动填写模型名"
                } else {
                    "点右上角「拉取模型列表」，即可看到每个模型支持的能力与思考强度档位"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.padding(start = 4.dp),
            )
        }

        // 自定义 provider 额外保留手动输入框兜底：中转站不一定兼容 GET /models
        if (isCustom) {
            CustomModelInputField(
                modelName = customModelInput,
                // 手动输入模型名：同步 selectedModelName 确保保存时拿到最新值；
                // 手输模型不在目录中，元数据自然查不到 → effort 保持用户所选/null
                onModelNameChange = {
                    customModelInput = it
                    selectedModelName = it
                },
                focusManager = focusManager,
            )
        }

        // ---- 思考强度档位选择（透传上游目录声明的档位）----
        // 有档位元数据时渲染选择行；已拉取到目录但该模型未声明档位时说明原因，
        // 未拉取时给出引导——避免用户完全不知道这里还能配思考强度。
        val effortChoices = selectedModelMeta?.supportedEfforts.orEmpty()
        if (effortChoices.isNotEmpty()) {
            EffortSelectorRow(
                efforts = effortChoices,
                defaultEffort = selectedModelMeta?.defaultEffort,
                selectedEffort = selectedEffort,
                onEffortSelected = { effort -> selectedEffort = effort },
            )
        } else if (hasModels && selectedModelName.isNotBlank()) {
            Text(
                "该模型未声明思考强度档位，将按上游默认值运行（不发送 effort 参数）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.padding(start = 4.dp),
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // ---- 保存按钮 ----
        val isModelSelected = selectedModelName.isNotBlank()
        // 保存条件：API key 非空 + （自定义需填写 baseUrl）+ 已选择模型
        val canSave = apiKeyInput.isNotBlank() &&
            (!isCustom || customBaseUrl.isNotBlank()) &&
            isModelSelected

        SaveConfigButton(
            enabled = canSave,
            text = if (isEditMode) "保存修改" else "开始对话",
            onClick = {
                focusManager.clearFocus()
                onSave(
                    selectedProvider,
                    apiKeyInput.trim(),
                    if (isCustom) customModelInput.trim() else selectedModelName,
                    if (isCustom) customBaseUrl.trim().let { if (it.isNotEmpty() && !it.startsWith("http://") && !it.startsWith("https://")) "https://$it" else it } else "",
                    // selectedEffort = null 表示"跟随上游默认"，请求时不发送 effort 参数；
                    // 选了具体档位则原样透传给上游
                    selectedEffort,
                )
            },
        )
    }
}

/**
 * Provider 展开式选择器 —— 圆角卡片形式，点击展开/收起显示所有可选 Provider。
 *
 * @param selectedProviderIndex 当前选中的 provider 在 builtInProviders 中的索引
 * @param dropdownExpanded 下拉菜单是否展开
 * @param onProviderSelected 用户点击某个 provider 时触发，传入该 provider 的索引；
 *                           调用方需负责更新 selectedProviderIndex 及重置模型列表等状态
 * @param onDropdownToggle 用户点击头部区域时触发，用于切换展开/收起状态
 */
@Composable
private fun ProviderSelectorCard(
    selectedProviderIndex: Int,
    dropdownExpanded: Boolean,
    onProviderSelected: (index: Int) -> Unit,
    onDropdownToggle: () -> Unit,
) {
    val selectedProvider = builtInProviders[selectedProviderIndex]

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // clip 裁剪动画溢出，保持圆角
                .clip(RoundedCornerShape(16.dp)),
        ) {
            // 头部：展示当前选中的 provider，点击展开/收起
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onDropdownToggle() }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        selectedProvider.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (selectedProvider.name != "自定义") {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            when (selectedProvider.apiType) {
                                ApiType.OPENAI_COMPATIBLE -> "OpenAI 兼容"
                                ApiType.ANTHROPIC -> "Anthropic"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Icon(
                    if (dropdownExpanded) LucideIcons.ChevronUp
                    else LucideIcons.ChevronDown,
                    contentDescription = if (dropdownExpanded) "收起" else "展开",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 展开区域：所有 provider 选项，带淡入淡出 + 展开/收起动画
            AnimatedVisibility(
                visible = dropdownExpanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    builtInProviders.forEachIndexed { index, provider ->
                        val isSelected = index == selectedProviderIndex
                        // 每个 provider 选项行 —— 选中项以主题色高亮 + 加粗 + 勾号
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) { onProviderSelected(index) }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    provider.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (isSelected)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.onSurface,
                                )
                                if (provider.name != "自定义") {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        when (provider.apiType) {
                                            ApiType.OPENAI_COMPATIBLE -> "OpenAI 兼容"
                                            ApiType.ANTHROPIC -> "Anthropic"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            // 选中标记：主题色勾号
                            if (isSelected) {
                                Icon(
                                    LucideIcons.Check,
                                    contentDescription = "已选中",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp),
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
 * API Key 输入框 —— 带密码显隐切换的 OutlinedTextField。
 *
 * @param apiKey 当前输入的 API key 值
 * @param onApiKeyChange API key 内容变化时触发，传入新值；
 *                       调用方通常需在此重置模型列表（旧 key 获取的列表可能无效）
 * @param apiKeyVisible 是否以明文显示 API key
 * @param onToggleVisibility 用户点击显隐切换图标时触发
 * @param focusManager 焦点管理器，用于键盘 Done 动作时收起键盘
 */
@Composable
private fun ApiKeyInputField(
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    apiKeyVisible: Boolean,
    onToggleVisibility: () -> Unit,
    focusManager: FocusManager,
) {
    OutlinedTextField(
        value = apiKey,
        onValueChange = onApiKeyChange,
        label = { Text("API Key") },
        placeholder = { Text("sk-...（OpenAI）或 sk-ant-...（Anthropic）") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        // 密码模式：默认隐藏，点击眼睛图标切换明文
        visualTransformation = if (apiKeyVisible) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        trailingIcon = {
            IconButton(onClick = onToggleVisibility) {
                Icon(
                    if (apiKeyVisible) LucideIcons.EyeOff else LucideIcons.Eye,
                    contentDescription = if (apiKeyVisible) "隐藏密钥" else "显示密钥",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(
            onDone = { focusManager.clearFocus() },
        ),
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(),
    )
}

/**
 * 模型选择区域的标题行 —— 左侧"3. 选择模型"，右侧显眼的"拉取模型列表"按钮。
 *
 * 历史缺陷：此处曾是一个 18dp 的小刷新图标，且对自定义 Provider 直接隐藏，
 * 用户进入配置页后看不到任何可点的入口，误以为功能没有实现。
 * 现改为带文字的按钮，并对所有 Provider 开放。
 *
 * @param isCustom 是否为自定义 provider（自定义时额外要求 baseUrl 已填写才能拉取）
 * @param isFetchingModels 是否正在从 API 获取模型列表
 * @param apiKeyInput 当前 API key 值，用于控制按钮的启用/禁用状态
 * @param baseUrlInput 自定义 provider 的 baseUrl，用于判断能否拉取
 * @param onFetchModels 用户点击拉取按钮时触发
 */
@Composable
private fun ModelSectionHeader(
    isCustom: Boolean,
    isFetchingModels: Boolean,
    apiKeyInput: String,
    baseUrlInput: String,
    onFetchModels: () -> Unit,
) {
    // 按钮可用性：API key 必填；自定义 Provider 还需 baseUrl
    val canFetch = apiKeyInput.isNotBlank() && (!isCustom || baseUrlInput.isNotBlank())

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "3. 选择模型",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // 带文字的拉取按钮：比小图标更容易被发现，所有 Provider 均可见
        if (isFetchingModels) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    "拉取中...",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { if (canFetch) onFetchModels() }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    LucideIcons.RefreshCw,
                    contentDescription = "拉取模型列表",
                    modifier = Modifier.size(16.dp),
                    tint = if (canFetch) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    "拉取模型列表",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (canFetch) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                )
            }
        }
    }
}

/**
 * 思考强度档位选择行 —— 档位列表完全来自上游模型目录（supported_efforts），透传不造默认。
 *
 * 交互：横排圆角小胶囊，点击即选。首项固定为"默认"（选中时 null，
 * 发请求不带 effort 参数，跟随上游默认值）；其后是上游声明的各档位，
 * 上游标注的 default_effort 会在档位名后缀"（上游默认）"提示。
 *
 * @param efforts       上游声明的档位列表（如 ["low","high","max"]），非空才渲染此行
 * @param defaultEffort 上游标注的默认档位，用于提示文案；null 表示上游未标注
 * @param selectedEffort 当前选中的档位；null 表示"跟随上游默认"
 * @param onEffortSelected 用户点击某档位时触发；点击"默认"时传 null
 */
@Composable
private fun EffortSelectorRow(
    efforts: List<String>,
    defaultEffort: String?,
    selectedEffort: String?,
    onEffortSelected: (String?) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            "思考强度",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 首项"默认"：null = 不发送参数
            EffortChip(
                label = "默认" + (defaultEffort?.let { "($it)" } ?: ""),
                selected = selectedEffort == null,
                onClick = { onEffortSelected(null) },
                modifier = Modifier.weight(1f),
            )
            // 上游声明的各档位，原样透传
            efforts.forEach { effort ->
                EffortChip(
                    label = effort,
                    selected = selectedEffort == effort,
                    onClick = { onEffortSelected(effort) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * 单个思考强度档位胶囊 —— 选中时主题色填充，未选中时描边样式。
 *
 * @param label    档位显示文本（如 "low"、"默认(high)"）
 * @param selected 是否选中
 * @param onClick  点击回调
 * @param modifier 布局修饰符
 */
@Composable
private fun EffortChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.clip(RoundedCornerShape(10.dp)),
    ) {
        Box(
            modifier = Modifier
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onClick() }
                .padding(vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        }
    }
}

/**
 * 模型展开式选择器 —— 与 ProviderSelectorCard 同款的圆角卡片样式。
 * 从 API 获取到的模型目录中选取一个模型；每个条目展示上游下发的能力标签
 * （思考强度档位、多模态、上下文长度），无元数据（官方 API）时只显示模型名。
 *
 * @param models 从 API 获取到的模型元数据列表
 * @param selectedModelName 当前选中的模型名，为空表示尚未选择
 * @param onModelSelected 用户点击某个模型时触发，传入被选中的模型 ID；
 *                        调用方需负责更新 selectedModelName 及收起下拉菜单
 * @param dropdownExpanded 下拉菜单是否展开
 * @param onDropdownToggle 用户点击头部区域时触发，用于切换展开/收起状态
 */
@Composable
private fun ModelSelectorCard(
    models: List<ModelMetadata>,
    selectedModelName: String,
    onModelSelected: (model: String) -> Unit,
    dropdownExpanded: Boolean,
    onDropdownToggle: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp)),
        ) {
            // 头部：当前选中的模型名 + 展开/收起箭头
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onDropdownToggle() }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    selectedModelName.ifEmpty { "请选择一个模型" },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (selectedModelName.isNotEmpty()) FontWeight.Medium else FontWeight.Normal,
                    color = if (selectedModelName.isEmpty())
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    else
                        MaterialTheme.colorScheme.onSurface,
                )
                Icon(
                    if (dropdownExpanded) LucideIcons.ChevronUp
                    else LucideIcons.ChevronDown,
                    contentDescription = if (dropdownExpanded) "收起" else "展开",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 展开区域：所有可用模型，带淡入淡出 + 展开/收起动画
            AnimatedVisibility(
                visible = dropdownExpanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    models.forEach { model ->
                        val isModelSelected = model.id == selectedModelName
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) { onModelSelected(model.id) }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    model.id,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isModelSelected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (isModelSelected)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.onSurface,
                                )
                                // 能力标签行 —— 仅当上游声明了元数据时显示
                                val tags = modelTagLabels(model)
                                if (tags.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        tags.joinToString(" · "),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                    )
                                }
                            }
                            if (isModelSelected) {
                                Icon(
                                    LucideIcons.Check,
                                    contentDescription = "已选中",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp),
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
 * 生成单个模型的能力标签文本列表（用于模型选择下拉条目）。
 *
 * 标签顺序：多模态 → 思考档位 → 上下文长度。全部来自上游目录下发值，
 * 未声明的不显示（官方 OpenAI/DeepSeek API 无元数据 → 空列表 → 不渲染标签行）。
 *
 * @param model 模型元数据
 * @return 标签文本列表，如 ["可看图", "思考: low/high/max", "1M 上下文"]
 */
private fun modelTagLabels(model: ModelMetadata): List<String> {
    val tags = mutableListOf<String>()
    when (model.supportsImage) {
        true -> tags.add("可看图")
        false -> tags.add("无视觉")
        null -> {} // 上游未声明，不标注
    }
    if (model.hasEfforts) {
        tags.add("思考: " + model.supportedEfforts.joinToString("/"))
    }
    model.contextLength?.let { len ->
        tags.add(if (len >= 1_000_000) "${len / 1_000_000}M 上下文" else "${len / 1000}K 上下文")
    }
    return tags
}

/**
 * 自定义模型名输入框 —— 仅在自定义 Provider 模式下显示。
 * 用户手动输入模型名称（因为自定义 Provider 无法自动获取模型列表）。
 *
 * @param modelName 当前输入的模型名
 * @param onModelNameChange 模型名变化时触发，传入新值；
 *                          调用方需同步更新 selectedModelName 以确保保存时拿到最新值
 * @param focusManager 焦点管理器，用于键盘 Done 动作时收起键盘
 */
@Composable
private fun CustomModelInputField(
    modelName: String,
    onModelNameChange: (String) -> Unit,
    focusManager: FocusManager,
) {
    OutlinedTextField(
        value = modelName,
        onValueChange = onModelNameChange,
        label = { Text("模型名称") },
        placeholder = { Text("例如 gpt-4o, claude-sonnet-4-20250514") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(
            onDone = { focusManager.clearFocus() },
        ),
        shape = RoundedCornerShape(12.dp),
    )
}

/**
 * 保存配置按钮 —— 点击后将完整 API 配置传递给调用方。
 *
 * @param enabled 按钮是否可点击；
 *                条件为 API key 非空 + 已选择模型 + 自定义模式下 baseUrl 非空
 * @param text 按钮文案（"开始对话"或"保存修改"）
 * @param onClick 用户点击按钮时触发；
 *                调用方在此收集所有配置数据并调用外层 onSave 回调
 */
@Composable
private fun SaveConfigButton(
    enabled: Boolean,
    text: String,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp),
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}
