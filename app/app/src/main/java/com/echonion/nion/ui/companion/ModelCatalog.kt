package com.echonion.nion.ui.companion

import org.json.JSONArray
import org.json.JSONObject

/**
 * 单个模型的元数据 —— 由上游模型目录（/v1/models）下发的增强字段。
 *
 * 本机实际在用的两类上游（WB Switch relay、TWA/Magpie 网关）都在标准 OpenAI
 * /v1/models 响应的每个条目上追加了能力元数据，字段名有两套变体：
 * - WB Switch / TWA：supported_efforts（字符串数组）+ default_effort + supports_image
 *   + supports_reasoning + context_length + max_output_tokens
 * - Magpie：reasoning_levels（[{effort:"low"},...] 或字符串数组）+ modalities.input
 *   （["text","image"]）+ context_length + max_output_tokens
 *
 * 所有字段均可为 null：普通 OpenAI/DeepSeek 官方 API 只返回 {id, object}，
 * 此时元数据全空，UI 不显示能力标签，effort 档位列表为空（不发送参数，
 * 行为与上游默认一致——这正是"透传"语义：上游给多少用多少，没有就不发）。
 *
 * @property id                   模型 ID（OpenAI 格式 data[].id）
 * @property supportedEfforts     思考强度档位列表（如 ["low","high","max"]），
 *                                空表示上游未声明，发请求时不带 effort 参数
 * @property defaultEffort        上游默认档位（如 "high"），UI 用它做预选
 * @property supportsImage        是否支持图片输入；null = 上游未声明（不确定）
 * @property supportsReasoning    是否为推理模型；null = 未声明
 * @property contextLength        上下文长度（token 数）
 * @property maxOutputTokens      最大输出 token 数
 */
data class ModelMetadata(
    val id: String,
    val supportedEfforts: List<String> = emptyList(),
    val defaultEffort: String? = null,
    val supportsImage: Boolean? = null,
    val supportsReasoning: Boolean? = null,
    val contextLength: Long? = null,
    val maxOutputTokens: Long? = null,
) {
    /** 是否声明了至少一项思考强度档位（决定 UI 是否显示档位选择行） */
    val hasEfforts: Boolean get() = supportedEfforts.isNotEmpty()
}

/**
 * 模型目录 —— 解析 /v1/models 响应并提取每个模型的增强元数据。
 *
 * 兼容三类响应：
 * 1. 标准 OpenAI（官方 API）：data[].id，无任何元数据 → 元数据字段全为默认空值
 * 2. WB Switch / TWA 变体：data[].supported_efforts / default_effort /
 *    supports_image / supports_reasoning / context_length / max_output_tokens
 * 3. Magpie 变体：data[].reasoning_levels（["low",...] 或 [{effort:"low"},...]）/
 *    modalities.input / context_length / max_output_tokens
 *
 * 三类解析互不冲突：字段名不同，逐个尝试即可；缺字段自然落到空值。
 */
object ModelCatalog {

    /**
     * 解析 /v1/models 响应 JSON，返回按原始顺序排列的模型元数据列表。
     *
     * @param responseText HTTP 响应体
     * @return 模型元数据列表；响应不含 data 数组时返回空列表
     */
    fun parse(responseText: String): List<ModelMetadata> {
        val json = JSONObject(responseText)
        val data = json.optJSONArray("data") ?: return emptyList()
        val models = mutableListOf<ModelMetadata>()
        for (i in 0 until data.length()) {
            val m = data.optJSONObject(i) ?: continue
            models.add(parseModel(m))
        }
        return models
    }

    /**
     * 解析单个模型条目，提取 effort 档位、多模态、上下文等元数据。
     * 每个字段独立尝试，任何字段缺失/格式不符都不影响其他字段。
     */
    private fun parseModel(m: JSONObject): ModelMetadata {
        val id = m.optString("id", "")
        return ModelMetadata(
            id = id,
            supportedEfforts = parseEfforts(m),
            defaultEffort = m.optString("default_effort", "").takeIf { it.isNotEmpty() },
            supportsImage = parseSupportsImage(m),
            supportsReasoning = when {
                m.has("supports_reasoning") -> m.optBoolean("supports_reasoning", false)
                m.has("reasoning") -> m.optBoolean("reasoning", false)
                else -> null
            },
            contextLength = m.optLong("context_length", 0L).takeIf { it > 0 },
            maxOutputTokens = (m.optLong("max_output_tokens", 0L).takeIf { it > 0 }
                ?: m.optLong("max_tokens", 0L).takeIf { it > 0 }),
        )
    }

    /**
     * 提取思考强度档位列表。两套字段名都试：
     * - supported_efforts: ["low","high","max"]（WB Switch / TWA）
     * - reasoning_levels: ["low",...] 或 [{"effort":"low"},...]（Magpie）
     * 全部缺失时返回空列表。
     */
    private fun parseEfforts(m: JSONObject): List<String> {
        // WB Switch / TWA：字符串数组
        m.optJSONArray("supported_efforts")?.let { arr ->
            val list = (0 until arr.length()).mapNotNull { idx ->
                val v = arr.opt(idx)
                if (v is String) v else null
            }
            if (list.isNotEmpty()) return list
        }
        // Magpie：字符串数组或对象数组（取 effort 键）
        m.optJSONArray("reasoning_levels")?.let { arr ->
            val list = (0 until arr.length()).mapNotNull { idx ->
                val v = arr.opt(idx)
                when (v) {
                    is String -> v
                    is JSONObject -> v.optString("effort", "").takeIf { it.isNotEmpty() }
                    else -> null
                }
            }
            if (list.isNotEmpty()) return list
        }
        return emptyList()
    }

    /**
     * 提取多模态（图片输入）支持标记。
     * - supports_image: true/false（WB Switch / TWA）
     * - modalities.input 数组包含 "image"（Magpie）
     * 两处都缺时返回 null（上游未声明，UI 不做判断）。
     */
    private fun parseSupportsImage(m: JSONObject): Boolean? {
        if (m.has("supports_image")) {
            return when (m.opt("supports_image")) {
                is Boolean -> m.optBoolean("supports_image", false)
                else -> null
            }
        }
        val modalities = m.optJSONObject("modalities")
        val input = modalities?.optJSONArray("input") ?: return null
        for (i in 0 until input.length()) {
            if (input.optString(i) == "image") return true
        }
        return false
    }

    /**
     * 将 JSON 错误响应转为对用户友好的换模型提示语。
     *
     * 发送图片到不支持视觉的模型时，各上游报错文案不同（OpenAI 官方、
     * WB Switch、Magpie 各有一套），逐个匹配关键词归类为统一提示；
     * 匹配不到时返回 null（调用方沿用原始错误信息）。
     *
     * @param errorText API 返回的错误文本（HTTP body 或异常 message）
     * @param modelName 当前使用的模型名
     * @return 人话提示；无法识别时为 null
     */
    fun imageNotSupportedHint(errorText: String, modelName: String): String? {
        val lower = errorText.lowercase()
        val isImageRelated = listOf("image", "vision", "multimodal", "图片", "视觉", "多模态").any { it in lower }
        val isNotSupported = listOf("not support", "unsupported", "does not", "invalid content",
            "不支持", "无法处理").any { it in lower }
        return if (isImageRelated && isNotSupported) {
            "当前模型「$modelName」不支持图片输入。请在配置中换一个支持视觉的模型（拉取列表里标有\"可看图\"的），或去掉图片后重发。"
        } else null
    }
}
