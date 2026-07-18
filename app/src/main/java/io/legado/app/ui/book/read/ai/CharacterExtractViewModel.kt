package io.legado.app.ui.book.read.ai

import androidx.lifecycle.MutableLiveData
import io.legado.app.base.BaseViewModel
import io.legado.app.data.appDb
import io.legado.app.data.entities.KnowledgePoint
import io.legado.app.help.book.BookHelp
import io.legado.app.help.config.AiConfig
import io.legado.app.help.http.okHttpClient
import io.legado.app.model.ReadBook
import io.legado.app.utils.GSON
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 角色提取 ViewModel
 *
 * 职责：
 * 1. 从小说指定章节提取角色
 * 2. 流式输出进度（不自动保存）
 * 3. 暴露保存方法由用户主动触发
 */
class CharacterExtractViewModel(application: android.app.Application) : BaseViewModel(application) {

    data class CharacterExtractResult(
        val name: String,
        val description: String = "",
        val personality: String = "",
        val background: String = "",
        val speakingStyle: String = ""
    )

    /** 进度/日志文本 */
    val logLiveData = MutableLiveData<String>("")

    /** 当前提取到的角色列表 */
    val resultsLiveData = MutableLiveData<List<CharacterExtractResult>>(emptyList())

    /** 是否正在提取 */
    val isLoadingLiveData = MutableLiveData(false)

    /** 错误消息 */
    val errorLiveData = MutableLiveData<String?>(null)

    /** 是否已提取完毕 */
    val isDoneLiveData = MutableLiveData(false)

    private var extractedRoles: List<CharacterExtractResult> = emptyList()

    /** 由 UI 层传入编辑后的结果，覆盖提取的原始结果 */
    fun setEditedResults(results: List<CharacterExtractResult>) {
        extractedRoles = results
    }

    /**
     * 开始提取角色
     */
    fun startExtract(
        bookUrl: String,
        bookName: String,
        chapterIndexes: List<Int>
    ) {
        isLoadingLiveData.postValue(true)
        isDoneLiveData.postValue(false)
        logLiveData.postValue("")

        execute {
            try {
                val result = extractCharacters(bookUrl, bookName, chapterIndexes)
                extractedRoles = result
                resultsLiveData.postValue(result)
                isDoneLiveData.postValue(true)
            } catch (e: Exception) {
                errorLiveData.postValue(e.message ?: "提取失败")
            }
            isLoadingLiveData.postValue(false)
        }
    }

    /**
     * 保存当前提取结果到知识库
     */
    fun saveResults(bookName: String) {
        if (extractedRoles.isEmpty()) return
        execute {
            for (role in extractedRoles) {
                val content = buildCharacterContent(role)
                appDb.knowledgePointDao.insert(
                    KnowledgePoint(
                        title = role.name,
                        content = content,
                        tags = bookName,
                        category = "character",
                        subCategory = "novel-character",
                        novelName = bookName,
                        sortOrder = 0,
                        createTime = System.currentTimeMillis(),
                        updateTime = System.currentTimeMillis()
                    )
                )
            }
        }
    }

    // ===== 提取核心逻辑（从 AiChatViewModel 搬过来，去掉自动保存） =====

    private suspend fun extractCharacters(
        bookUrl: String,
        bookName: String,
        chapterIndexes: List<Int>
    ): List<CharacterExtractResult> = withContext(Dispatchers.IO) {
        val book = ReadBook.book ?: throw Exception("未打开书籍")
        if (chapterIndexes.isEmpty()) throw Exception("请选择至少一个章节")
        val bookNameFinal = bookName.ifBlank { book.name.ifBlank { "未命名小说" } }

        // 1. 读取所有选中的章节内容
        appendLog("📖 读取章节内容")
        val chunks = mutableListOf<String>()
        for ((idx, ci) in chapterIndexes.withIndex()) {
            appendLog("📖 读取第 ${ci + 1} 章（${idx + 1}/${chapterIndexes.size}）")
            val chapter = appDb.bookChapterDao.getChapterList(bookUrl, ci, ci).firstOrNull()
                ?: continue
            val content = BookHelp.getContent(book, chapter) ?: ""
            if (content.isNotBlank()) {
                val display = if (content.length > 4000) content.substring(0, 4000) + "\n……（截断）" else content
                val title = chapter.title.ifBlank { "第${ci + 1}章" }
                chunks.add("【$title】\n$display")
            }
        }
        if (chunks.isEmpty()) throw Exception("未能读取到章节内容")

        // 2. 逐段发送粗提炼
        val rawSummaries = mutableListOf<String>()
        val batchSize = 3
        for (batchStart in chunks.indices step batchSize) {
            val batch = chunks.subList(batchStart, minOf(batchStart + batchSize, chunks.size))
            val batchPrompt = buildChunkBatchPrompt(batch, rawSummaries.size + 1, chunks.size)
            appendLog("🤖 AI 分析第 ${batchStart + 1}-${batchStart + batch.size} 段")
            try {
                val sb = StringBuilder()
                val response = chatCompletionStreaming(batchPrompt) { delta ->
                    sb.append(delta)
                    // 边收边更新日志（显示实时生成内容）
                    appendStreamingLog("📝 生成中：${sb.toString()}")
                }
                rawSummaries.add(sb.toString().ifBlank { response })
            } catch (e: Exception) {
                rawSummaries.add("【批次 ${batchStart / batchSize + 1} 提取失败：${e.message}】")
            }
        }

        // 3. 合并 + 提取角色
        appendLog("🔄 正在合并提取角色")
        val mergePrompt = buildMergePrompt(rawSummaries)
        val mergedSb = StringBuilder()
        val mergedResponse = try {
            chatCompletionStreaming(mergePrompt) { delta ->
                mergedSb.append(delta)
                appendStreamingLog("📝 合并生成中：${mergedSb.toString()}")
            }
            mergedSb.toString()
        } catch (e: Exception) {
            throw Exception("合并提取失败：${e.message}")
        }

        // 4. JSON 解析
        val roles = parseCharacterJson(mergedResponse)
        appendLog("✅ 提取完成！共 ${roles.size} 个角色：${roles.joinToString("、") { it.name }}")

        return@withContext roles
    }

    private suspend fun chatCompletion(userContent: String): String = withContext(Dispatchers.IO) {
        val apiKey = AiConfig.apiKey
        val model = AiConfig.model
        val url = AiConfig.normalizedChatUrl
        val jsonBody = GSON.toJson(mapOf(
            "model" to model,
            "messages" to listOf(mapOf("role" to "user", "content" to userContent)),
            "max_tokens" to 8192,
            "temperature" to 0.7
        ))
        val request = Request.Builder()
            .url(url)
            .post(jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .addHeader("Authorization", "Bearer ${apiKey}")
            .build()
        val response = okHttpClient.newCall(request).execute()
        val body = response.body?.string() ?: throw Exception("响应为空")
        if (!response.isSuccessful) throw Exception("HTTP ${response.code}: $body")
        GSON.fromJson(body, Map::class.java)?.let { map ->
            @Suppress("UNCHECKED_CAST")
            val choices = map["choices"] as? List<Map<String, Any?>>
            val message = choices?.firstOrNull()?.get("message") as? Map<String, Any?>
            val content = message?.get("content") as? String
            if (content != null) content else throw Exception("API 返回格式异常")
        } ?: throw Exception("JSON 解析失败")
    }

    /**
     * 流式版 chatCompletion：边收边 append 到日志，避免"等全部才显示"
     */
    private suspend fun chatCompletionStreaming(
        userContent: String,
        onDelta: (String) -> Unit
    ): String = withContext(Dispatchers.IO) {
        val apiKey = AiConfig.apiKey
        val model = AiConfig.model
        val url = AiConfig.normalizedChatUrl
        val jsonBody = GSON.toJson(mapOf(
            "model" to model,
            "messages" to listOf(mapOf("role" to "user", "content" to userContent)),
            "max_tokens" to 8192,
            "temperature" to 0.7,
            "stream" to true
        ))
        val request = Request.Builder()
            .url(url)
            .post(jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .addHeader("Authorization", "Bearer ${apiKey}")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .build()
        val response = okHttpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            throw Exception("HTTP ${response.code}: ${response.body?.string() ?: ""}")
        }
        val source = response.body?.source() ?: throw Exception("响应为空")
        val buffer = StringBuilder()
        // 逐行读取 SSE：data: {json}
        while (!source.exhausted()) {
            val line = source.readUtf8Line() ?: continue
            if (!line.startsWith("data:")) continue
            val data = line.removePrefix("data:").trim()
            if (data == "[DONE]") break
            try {
                val obj = GSON.fromJson(data, Map::class.java) ?: continue
                @Suppress("UNCHECKED_CAST")
                val choices = obj["choices"] as? List<Map<String, Any?>> ?: continue
                val delta = (choices.firstOrNull()?.get("delta") as? Map<String, Any?>) ?: continue
                val piece = delta["content"] as? String
                if (!piece.isNullOrEmpty()) {
                    buffer.append(piece)
                    onDelta(piece)
                }
            } catch (_: Exception) {
                // 跳过无法解析的行
            }
        }
        buffer.toString().ifBlank { throw Exception("API 返回为空") }
    }

    private fun appendLog(text: String) {
        val current = logLiveData.value ?: ""
        logLiveData.postValue(if (current.isEmpty()) text else "$current\n$text")
    }

    /**
     * 流式日志：用同一行覆盖"生成中"状态，避免日志被刷屏
     * 只要内容变化就更新最后一行（以 📝 开头视为流式行）
     */
    private fun appendStreamingLog(text: String) {
        val current = logLiveData.value ?: ""
        val lines = current.lines().toMutableList()
        // 找到最后一个流式行（📝 开头）并替换，否则追加
        val lastIdx = lines.indexOfLast { it.startsWith("📝") }
        if (lastIdx >= 0) {
            lines[lastIdx] = text
        } else {
            lines.add(text)
        }
        logLiveData.postValue(lines.joinToString("\n"))
    }

    // ===== 以下方法从 AiChatViewModel 复制 =====

    private fun buildChunkBatchPrompt(
        chunks: List<String>,
        startIndex: Int,
        total: Int
    ): String {
        val batchText = chunks.joinToString("\n\n---\n\n")
        return """请阅读以下小说片段（第 $startIndex-${startIndex + chunks.size - 1} 段 / 共 $total 段），并提炼信息。

请输出：
1. 本片段剧情摘要
2. 出现的主要角色
3. 角色关系与性格线索
4. 重要世界观、地点、事件

要求：
- 简洁但不要漏掉关键设定
- 不要加入原文没有的信息

小说片段：
$batchText"""
    }

    private fun buildMergePrompt(summaries: List<String>): String {
        val joinedSummaries = summaries.joinToString("\n\n---\n\n")
        return """请合并以下小说分段摘要，生成可用于角色扮演聊天的小说设定档，并提取适合 AI 扮演的角色。

请只输出 JSON，不要使用 Markdown 代码块：
{
  "summary": "小说总设定与剧情摘要",
  "roles": [
    {
      "name": "角色名",
      "description": "角色简介",
      "personality": "性格设定",
      "background": "背景故事",
      "speakingStyle": "说话风格"
    }
  ]
}

要求：
- 优先提取女性角色，但不要编造不存在的角色
- 每个角色只输出 name、description、personality、background、speakingStyle 这 5 个字段，不要输出开场白、补充设定或其他字段
- 只输出反复出现、戏份充足或推动主线的重要角色；把出场次数明显低、戏份明显少、路人、临时名字删掉，不要放进 roles
- roles 最多 5 个，只保留最主要的人物
- 所有字段都用中文
- JSON 必须可解析

分段摘要：
$joinedSummaries"""
    }

    private fun parseCharacterJson(json: String): List<CharacterExtractResult> {
        var text = json.trim()
        if (text.startsWith("```")) {
            text = text.replaceFirst(Regex("^```(?:json)?\\s*"), "")
                .replaceFirst(Regex("\\s*```$"), "")
        }
        val objStart = text.indexOf('{')
        val objEnd = text.lastIndexOf('}')
        if (objStart >= 0 && objEnd > objStart) {
            text = text.substring(objStart, objEnd + 1)
        }
        return try {
            val map = io.legado.app.utils.GSON.fromJson(text, Map::class.java) as? Map<*, *>
            val rawRoles = map?.get("roles") as? List<*>
            if (rawRoles != null) {
                rawRoles.mapNotNull { item ->
                    val roleMap = item as? Map<*, *> ?: return@mapNotNull null
                    val name = (roleMap["name"] as? String)?.trim() ?: return@mapNotNull null
                    if (name.isEmpty()) return@mapNotNull null
                    CharacterExtractResult(
                        name = name,
                        description = (roleMap["description"] as? String)?.trim() ?: "",
                        personality = (roleMap["personality"] as? String)?.trim() ?: "",
                        background = (roleMap["background"] as? String)?.trim() ?: "",
                        speakingStyle = (roleMap["speakingStyle"] as? String)?.trim() ?: ""
                    )
                }
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun buildCharacterContent(role: CharacterExtractResult): String {
        return buildString {
            if (role.description.isNotBlank()) {
                append("简介：${role.description}\n\n")
            }
            if (role.personality.isNotBlank()) {
                append("性格：${role.personality}\n\n")
            }
            if (role.background.isNotBlank()) {
                append("背景：${role.background}\n\n")
            }
            if (role.speakingStyle.isNotBlank()) {
                append("说话风格：${role.speakingStyle}")
            }
        }.trim()
    }
}
