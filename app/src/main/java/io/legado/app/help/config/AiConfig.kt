package io.legado.app.help.config

import androidx.annotation.Keep
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.putPrefString
import splitties.init.appCtx

@Keep
data class AiMemoryItem(
    val id: Long = System.currentTimeMillis(),
    val chapterRange: String,
    val content: String,
    val messagesJson: String? = null
) {
    val preview: String get() = content.take(15) + if (content.length > 15) "..." else ""
}

/**
 * AI 助手相关配置
 */
object AiConfig {
    private const val KEY_AI_API_URL = "ai_api_url"
    private const val KEY_AI_API_KEY = "ai_api_key"
    private const val KEY_AI_MODEL = "ai_model"
    private const val KEY_AI_PERSONA = "ai_persona"
    private const val KEY_AI_PERSONA_TITLE = "ai_persona_title"
    private const val KEY_AI_PERSONA_MODE = "ai_persona_mode"
    private const val KEY_AI_MEMORY = "ai_memory"
    private const val KEY_AI_AVATAR = "ai_avatar"
    private const val KEY_USER_AVATAR = "user_avatar"
    private const val KEY_AI_TOOL_ENABLED = "ai_tool_enabled"
    private const val KEY_AI_TOOL_MAX_ROUNDS = "ai_tool_max_rounds"

    var apiUrl: String
        get() = appCtx.getPrefString(KEY_AI_API_URL, "https://api.openai.com/v1/chat/completions") ?: ""
        set(value) {
            appCtx.putPrefString(KEY_AI_API_URL, value)
        }

    var apiKey: String
        get() = appCtx.getPrefString(KEY_AI_API_KEY, "") ?: ""
        set(value) {
            appCtx.putPrefString(KEY_AI_API_KEY, value)
        }

    /**
     * 规范化后的 chat/completions 完整地址（只读）。
     * apiUrl 由用户填写，可能是 base URL（如 https://xxx/v1）或完整路径。
     * 提取角色、章节概要等实际请求必须打到 /chat/completions，否则上游返回 404。
     * 这里统一补齐，避免各调用点重复拼接导致行为不一致。
     */
    val normalizedChatUrl: String
        get() {
            val raw = apiUrl.trim().trimEnd('/')
            return when {
                raw.endsWith("/chat/completions", ignoreCase = true) -> raw
                raw.endsWith("/models", ignoreCase = true) ->
                    raw.removeSuffix("/models") + "/chat/completions"
                raw.endsWith("/completions", ignoreCase = true) ->
                    raw.substringBeforeLast('/') + "/chat/completions"
                else -> "$raw/chat/completions"
            }
        }

    var model: String
        get() = appCtx.getPrefString(KEY_AI_MODEL, "gpt-3.5-turbo") ?: ""
        set(value) {
            appCtx.putPrefString(KEY_AI_MODEL, value)
        }

    var persona: String
        get() = appCtx.getPrefString(KEY_AI_PERSONA, "你是一个擅长分析文学作品的 AI 助手，请结合用户发送的当下正在阅读的章节内容，回答用户的问题。如果用户想探讨剧情人物，请积极互动。") ?: ""
        set(value) {
            appCtx.putPrefString(KEY_AI_PERSONA, value)
        }

    var personaTitle: String
        get() = appCtx.getPrefString(KEY_AI_PERSONA_TITLE, "文学作品分析助手") ?: ""
        set(value) {
            appCtx.putPrefString(KEY_AI_PERSONA_TITLE, value)
        }

    // "template" or "custom"
    var personaMode: String
        get() = appCtx.getPrefString(KEY_AI_PERSONA_MODE, "template") ?: "template"
        set(value) {
            appCtx.putPrefString(KEY_AI_PERSONA_MODE, value)
        }

    var memoryList: List<AiMemoryItem>
        get() {
            val raw = appCtx.getPrefString(KEY_AI_MEMORY, "") ?: ""
            if (raw.isBlank()) return emptyList()
            return try {
                if (raw.trimStart().startsWith("[")) {
                    GSON.fromJsonArray<AiMemoryItem>(raw).getOrNull() ?: emptyList()
                } else {
                    listOf(AiMemoryItem(id = 0L, chapterRange = "未知章节", content = raw))
                }
            } catch (e: Exception) {
                listOf(AiMemoryItem(id = 0L, chapterRange = "未知章节", content = raw))
            }
        }
        set(value) {
            appCtx.putPrefString(KEY_AI_MEMORY, GSON.toJson(value))
        }

    var memory: String
        get() {
            return ""
        }
        set(value) {
            appCtx.putPrefString(KEY_AI_MEMORY, value)
        }

    var aiAvatar: String
        get() = appCtx.getPrefString(KEY_AI_AVATAR, "") ?: ""
        set(value) {
            appCtx.putPrefString(KEY_AI_AVATAR, value)
        }

    var userAvatar: String
        get() = appCtx.getPrefString(KEY_USER_AVATAR, "") ?: ""
        set(value) {
            appCtx.putPrefString(KEY_USER_AVATAR, value)
        }

    var toolEnabled: Boolean
        get() = appCtx.getPrefBoolean(KEY_AI_TOOL_ENABLED, true)
        set(value) {
            appCtx.putPrefBoolean(KEY_AI_TOOL_ENABLED, value)
        }

    /**
     * 工具调用循环最大轮数。读书交互不需要复杂多轮，默认 5（比 Hermes 的 90 激进收紧）。
     * 页面可设置修改；达上限后终止 agent 循环，避免死循环。
     */
    var toolMaxRounds: Int
        get() = appCtx.getPrefInt(KEY_AI_TOOL_MAX_ROUNDS, 5).coerceAtLeast(1)
        set(value) {
            appCtx.putPrefInt(KEY_AI_TOOL_MAX_ROUNDS, value.coerceAtLeast(1))
        }

    // ===== 梨园 Liyuan RP 连接配置 =====
    private const val KEY_LIYUAN_WS_URL = "liyuan_ws_url"

    var liyuanWsUrl: String
        get() = appCtx.getPrefString(KEY_LIYUAN_WS_URL, "ws://127.0.0.1:7620/ws") ?: ""
        set(value) {
            appCtx.putPrefString(KEY_LIYUAN_WS_URL, value)
        }
}
