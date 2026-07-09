package io.legado.app.ui.book.read.ai

import android.app.Application
import androidx.lifecycle.MutableLiveData
import io.legado.app.base.BaseViewModel
import io.legado.app.help.config.AiConfig
import io.legado.app.help.config.AiMemoryItem
import io.legado.app.help.http.okHttpClient
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.ai.tool.AiToolDef
import io.legado.app.ui.book.read.ai.tool.ToolExecuteResult
import io.legado.app.ui.book.read.ai.tool.ToolRouter
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.legado.app.data.appDb
import io.legado.app.help.book.BookHelp
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class AiChatViewModel(application: Application) : BaseViewModel(application) {

    companion object {
        private const val MAX_TOOL_ROUNDS = 90

        const val VOICE_DESIGN_PROMPT = "请为本章所有角色设计声线，要求：\n" +
            "1. 从章节对话中识别所有说话角色（旁白不算）\n" +
            "2. 为每个角色生成一段中文音色描述（1-4句），覆盖性别与年龄、音色/质感、情绪/语气基调、语速/节奏、人设/腔调中的至少3个维度\n" +
            "3. 用具体、可视化的描述，避免“普通”“正常”等模糊词，避免矛盾特征，不要写混响回声等后期处理\n" +
            "4. 输出格式：**角色名** + 换行 + 声线描述，每个角色之间空一行，不需要标签和使用说明"
    }

    val messagesLiveData = MutableLiveData<List<ChatMessage>>()
    val wordCountLiveData = MutableLiveData<Int>()
    val isGeneratingLiveData = MutableLiveData<Boolean>()
    val confirmationLiveData = MutableLiveData<ConfirmationRequest?>()
    val batchConfirmationLiveData = MutableLiveData<BatchConfirmationRequest?>()

    fun confirmAction(confirmed: Boolean) {
        confirmationLiveData.value?.deferred?.complete(confirmed)
        confirmationLiveData.postValue(null)
    }

    fun confirmBatchAction(confirmed: Boolean) {
        batchConfirmationLiveData.value?.deferred?.complete(confirmed)
        batchConfirmationLiveData.postValue(null)
        if (!confirmed) {
            // 用户拒绝批量操作时，自动注入一条提示消息
            synchronized(_messages) {
                _messages.add(
                    ChatMessage(
                        role = "assistant",
                        content = "【操作已取消】你已拒绝本次整理操作。如需调整，请告诉我：\n" +
                        "- 哪些书籍/书源需要移动到其他分组\n" +
                        "- 或者哪些操作需要修改"
                    )
                )
            }
            syncCache()
            messagesLiveData.postValue(_messages.toList())
        }
    }

    private val _messages = mutableListOf<ChatMessage>()
    val messages: List<ChatMessage> get() = _messages.toList()

    private val isGenerating = AtomicBoolean(false)

    private fun setGenerating(generating: Boolean) {
        isGenerating.set(generating)
        isGeneratingLiveData.postValue(generating)
    }

    private val wordCountJobVersion = java.util.concurrent.atomic.AtomicLong(0L)

    private val msgIdCounter = AtomicLong(0L)

    /**
     * 获取所有章节标题，供提取角色多选使用
     */
    fun getChapterTitles(bookUrl: String): List<String> {
        val chapters = appDb.bookChapterDao.getChapterList(bookUrl, 0, Int.MAX_VALUE)
        return chapters.map { it.title.ifBlank { "第${it.index + 1}章" } }
    }

    fun calculateWordCount(bookUrl: String, start: Int, end: Int) {
        val chapterSize = ReadBook.chapterSize
        val clampedStart = start.coerceIn(1, chapterSize.coerceAtLeast(1))
        val clampedEnd = end.coerceIn(1, chapterSize.coerceAtLeast(1))
        val st = minOf(clampedStart, clampedEnd)
        val ed = maxOf(clampedStart, clampedEnd)

        val myVersion = wordCountJobVersion.incrementAndGet()
        execute {
            var totalCount = 0
            val book = ReadBook.book ?: return@execute
            val chapterList = appDb.bookChapterDao.getChapterList(bookUrl, st - 1, ed - 1)
            for (chapter in chapterList) {
                if (wordCountJobVersion.get() != myVersion) return@execute
                val content = BookHelp.getContent(book, chapter)
                if (content != null) {
                    totalCount += content.length
                }
                // 加上本章用户想法的字数
                val thoughts = appDb.bookThoughtDao.getByChapter(book.name, book.author, chapter.index)
                for (t in thoughts) {
                    totalCount += t.selectedText.length + t.thought.length
                }
            }
            if (wordCountJobVersion.get() == myVersion) {
                wordCountLiveData.postValue(totalCount)
            }
        }
    }

    fun initMessages(start: Int, end: Int) {
        val currentBookUrl = ReadBook.book?.bookUrl ?: ""
        val cached = AiChatCache.state
        if (cached.bookUrl == currentBookUrl &&
            cached.chapterIndex == start &&
            cached.messages.isNotEmpty()
        ) {
            synchronized(_messages) {
                _messages.clear()
                _messages.addAll(cached.messages)
            }
            messagesLiveData.postValue(_messages.toList())
            return
        }

        execute {
            val systemPrompt = buildSystemPrompt(start, end)
            synchronized(_messages) {
                _messages.clear()
                _messages.add(ChatMessage(role = "system", content = systemPrompt))
            }
            AiChatCache.state = AiChatCache.State(
                bookUrl = currentBookUrl,
                chapterIndex = start,
                messages = _messages.toList()
            )
            messagesLiveData.postValue(_messages.toList())
        }
    }

    private suspend fun buildSystemPrompt(
        start: Int, end: Int,
        references: List<ReferenceItem>? = null
    ): String {
        val unavailable = "（内容不可用）"
        return withContext(Dispatchers.IO) {
            buildString {
                append("【人设与要求】\n")
                if (AiConfig.toolEnabled) {
                    append("\n\n【工具使用指南】\n")
                    append("你可以调用工具来查询和管理用户的书架、书源、阅读记录等数据。\n")
                    append("【确认机制】：所有写操作（含删除）均采用批量确认——同一轮AI调用的多个写操作合并为一次弹窗，用户统一确认或拒绝。请一次性调用所有需要的工具，不要一个一个来。\n")
                    append("【静默写操作（无需确认）】：save_book_progress、rate_book、set_book_note 直接执行。\n")
                    append("【bookUrl说明】：get_book_content、rate_book、save_book_progress、mark_book_status、set_book_note 的 bookUrl 参数需从 get_bookshelf 返回结果的 bookUrl 字段获取，请先查询书架再操作。例外：若系统提示词【当前阅读书籍信息】中已提供 bookUrl，则直接使用，无需再调 get_bookshelf。\n")
                    append("【书源数量限制】：get_book_sources 每次最多返回100条，用户书源可能超过500个。")
                    append("操作书源前请先用 get_source_groups 了解分组结构，再按分组分批查询。")
                    append("如果要处理所有书源，请分批操作，并主动告知用户。")
                }
                if (AiConfig.memory.isNotBlank()) {
                    append("\n\n【之前的对话记忆】\n")
                    append(AiConfig.memory)
                }
                // start/end 为 0 表示独立模式，不加载书籍信息和章节内容
                if (start > 0 && end > 0) {
                    val book = ReadBook.book
                    if (book != null) {
                        // 注入书籍基本信息
                        append("\n\n【当前阅读书籍信息】\n")
                        append("书名：${book.name}\n")
                        if (book.author.isNotBlank()) append("作者：${book.author}\n")
                        val intro = book.getDisplayIntro()
                        if (!intro.isNullOrBlank()) append("简介：$intro\n")
                        // 书源信息：originName 是书源名称，origin 是书源URL
                        if (book.originName.isNotBlank()) append("所属书源：${book.originName}\n")
                        append("bookUrl：${book.bookUrl}\n")
                        val chapterSize = ReadBook.chapterSize
                        if (chapterSize > 0) append("总章节数：$chapterSize\n")
                        val durChapterTitle = book.durChapterTitle
                        if (!durChapterTitle.isNullOrBlank()) append("当前阅读章节：$durChapterTitle（第${book.durChapterIndex + 1}章）\n")

                        // 注入用户所选章节内容（全量）
                        val clampedStart = start.coerceIn(1, chapterSize.coerceAtLeast(1))
                        val clampedEnd = end.coerceIn(1, chapterSize.coerceAtLeast(1))
                        val st = minOf(clampedStart, clampedEnd)
                        val ed = maxOf(clampedStart, clampedEnd)
                        val rangeDesc = if (st == ed) "第${st}章" else "第${st}章 ~ 第${ed}章"
                        append("\n\n【参考章节内容（$rangeDesc）】\n")
                        val chapterList = appDb.bookChapterDao.getChapterList(book.bookUrl, st - 1, ed - 1)
                        for (chapter in chapterList) {
                            val content = BookHelp.getContent(book, chapter) ?: continue
                            append("=== ${chapter.title} ===\n")
                            append(content)
                            append("\n\n")
                            // 注入用户在本章的想法（划线+评注）
                            val thoughts = appDb.bookThoughtDao.getByChapter(book.name, book.author, chapter.index)
                            if (thoughts.isNotEmpty()) {
                                append("【用户在本章的想法（共${thoughts.size}条）】\n")
                                thoughts.forEachIndexed { i, t ->
                                    append("${i + 1}. ")
                                    if (t.selectedText.isNotBlank()) {
                                        append("「${t.selectedText.take(300)}」")
                                    }
                                    if (t.thought.isNotBlank()) {
                                        if (t.selectedText.isNotBlank()) append(" → ")
                                        append(t.thought)
                                    }
                                    append("\n")
                                }
                                append("\n")
                            }
                        }

                        // 注入用户引用的参考信息（@章节/@知识点/@提示词）
                        if (!references.isNullOrEmpty()) {
                            append("\n\n【用户引用的参考信息】\n")
                            for (ref in references) {
                                when (ref.type) {
                                    "chapter" -> {
                                        val refBook = if (ref.bookUrl != null) appDb.bookDao.getBook(ref.bookUrl) else book
                                        val refChapter = if (ref.bookUrl != null && ref.chapterIndex != null)
                                            appDb.bookChapterDao.getChapter(ref.bookUrl, ref.chapterIndex) else null
                                        if (refBook != null && refChapter != null) {
                                            val refContent = BookHelp.getContent(refBook, refChapter)
                                            append("--- ${refChapter.title} ---\n")
                                            append(refContent ?: unavailable)
                                            append("\n")
                                        } else {
                                            append("【章节】${ref.title}${unavailable}\n")
                                        }
                                    }
                                    "knowledge" -> {
                                        if (ref.id != null) {
                                            val kp = appDb.knowledgePointDao.getById(ref.id)
                                            if (kp != null) {
                                                append("【知识点：${kp.title}】\n${kp.content}\n")
                                            } else {
                                                append("【知识点】${ref.title}${unavailable}\n")
                                            }
                                        } else {
                                            append("【知识点】${ref.title}${unavailable}\n")
                                        }
                                    }
                                    "prompt" -> {
                                        if (ref.id != null) {
                                            val wp = appDb.writingPromptDao.getById(ref.id)
                                            if (wp != null) {
                                                append("【提示词：${wp.title}】\n${wp.content}\n")
                                            } else {
                                                append("【提示词】${ref.title}${unavailable}\n")
                                            }
                                        } else {
                                            append("【提示词】${ref.title}${unavailable}\n")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    fun sendMessage(userText: String, start: Int, end: Int, references: List<ReferenceItem>? = null) {
        if (!isGenerating.compareAndSet(false, true)) return
        isGeneratingLiveData.postValue(true)
        if (userText.isBlank()) {
            setGenerating(false)
            return
        }

        synchronized(_messages) {
            _messages.add(ChatMessage(role = "user", content = userText, references = references))
        }
        syncCache()
        messagesLiveData.postValue(_messages.toList())

        execute {
            try {
                val newSystemPrompt = buildSystemPrompt(start, end, references)
                synchronized(_messages) {
                    if (_messages.isNotEmpty() && _messages.first().role == "system") {
                        _messages[0] = ChatMessage(role = "system", content = newSystemPrompt)
                    } else {
                        _messages.add(0, ChatMessage(role = "system", content = newSystemPrompt))
                    }
                }

                val toolsEnabled = AiConfig.toolEnabled
                if (toolsEnabled) {
                    val tools = AiToolDef.allTools
                    val responseMsgs = requestWithTools(_messages.toList(), tools)
                    synchronized(_messages) {
                        _messages.addAll(responseMsgs)
                    }
                } else {
                    // 流式输出：先放占位消息，SSE 逐块更新内容
                    val msgId = msgIdCounter.incrementAndGet()
                    synchronized(_messages) {
                        _messages.add(ChatMessage(id = msgId, role = "assistant", content = "", isStreaming = true))
                    }
                    messagesLiveData.postValue(_messages.toList())

                    val finalMsg = requestOpenAiMessageStreaming(_messages.toList(), msgId,
                        onDelta = { delta ->
                            synchronized(_messages) {
                                val idx = _messages.indexOfLast { it.id == msgId }
                                if (idx >= 0) {
                                    _messages[idx] = _messages[idx].copy(
                                        content = _messages[idx].content + delta
                                    )
                                }
                            }
                            messagesLiveData.postValue(_messages.toList())
                        },
                        onReasoningDelta = { delta ->
                            synchronized(_messages) {
                                val idx = _messages.indexOfLast { it.id == msgId }
                                if (idx >= 0) {
                                    _messages[idx] = _messages[idx].copy(
                                        reasoningContent = (_messages[idx].reasoningContent ?: "") + delta
                                    )
                                }
                            }
                            messagesLiveData.postValue(_messages.toList())
                        }
                    )

                    if (finalMsg.content.isBlank() && finalMsg.reasoningContent.isNullOrBlank()) {
                        throw Exception("响应内容为空")
                    }
                    synchronized(_messages) {
                        val idx = _messages.indexOfLast { it.id == msgId }
                        if (idx >= 0) {
                            _messages[idx] = finalMsg.copy(isStreaming = false)
                        } else {
                            _messages.add(finalMsg.copy(role = "assistant"))
                        }
                    }
                }
            } catch (e: Exception) {
                synchronized(_messages) {
                    _messages.add(ChatMessage(role = "assistant", content = "请求失败: ${e.message}"))
                }
            } finally {
                setGenerating(false)
                syncCache()
                messagesLiveData.postValue(_messages.toList())
            }
        }
    }

    fun saveSession(start: Int, end: Int) {
        if (_messages.isEmpty()) {
            getApplication<Application>().toastOnUi("当前没有会话可保存")
            return
        }

        execute {
            try {
                val rangeStr = if (start == 0 && end == 0) "独立会话" else if (start == end) "第${start}章" else "第${start}-${end}章"
                
                // 提取预览：第一条用户的消息
                val firstUserMsg = _messages.firstOrNull { it.role == "user" }?.content ?: "空对话"
                
                val messagesJson = GSON.toJson(_messages)
                val currentList = AiConfig.memoryList.toMutableList()
                currentList.add(AiMemoryItem(chapterRange = rangeStr, content = firstUserMsg, messagesJson = messagesJson))
                AiConfig.memoryList = currentList

                getApplication<Application>().toastOnUi("本次会话已保存")
            } catch (e: Exception) {
                getApplication<Application>().toastOnUi("保存失败: ${e.message}")
            }
        }
    }

    fun restoreSession(messagesJson: String?) {
        if (messagesJson.isNullOrBlank()) {
            getApplication<Application>().toastOnUi("无法恢复，记录为空")
            return
        }
        execute {
            try {
                val savedMessages = GSON.fromJsonArray<ChatMessage>(messagesJson).getOrNull()
                synchronized(_messages) {
                    _messages.clear()
                    if (savedMessages != null) {
                        _messages.addAll(savedMessages)
                    }
                }
                syncCache()
                messagesLiveData.postValue(_messages.toList())
                getApplication<Application>().toastOnUi("会话已恢复")
            } catch (e: Exception) {
                getApplication<Application>().toastOnUi("恢复会话失败: ${e.message}")
            }
        }
    }

    private fun syncCache() {
        val current = AiChatCache.state
        AiChatCache.state = current.copy(messages = _messages.toList())
    }

    /**
     * 清空当前聊天页面的全部对话（保留 system 消息）
     */
    fun clearMessages() {
        synchronized(_messages) {
            val systemMsg = _messages.firstOrNull { it.role == "system" }
            _messages.clear()
            if (systemMsg != null) {
                _messages.add(systemMsg)
            }
        }
        syncCache()
        messagesLiveData.postValue(_messages.toList())
    }

    /**
     * 获取指定显示位置的消息内容（position 是过滤掉 system/tool 后的可见列表下标）
     */
    fun getMessageAt(displayPosition: Int): ChatMessage? {
        synchronized(_messages) {
            val visibleIndices = _messages.indices
                .filter { _messages[it].role != "system" && _messages[it].role != "tool" }
            if (displayPosition < 0 || displayPosition >= visibleIndices.size) return null
            return _messages[visibleIndices[displayPosition]]
        }
    }

    /**
     * 直接修改指定显示位置的消息内容（不经过 API）
     */
    fun updateMessageAt(displayPosition: Int, newContent: String) {
        synchronized(_messages) {
            val visibleIndices = _messages.indices
                .filter { _messages[it].role != "system" && _messages[it].role != "tool" }
            if (displayPosition < 0 || displayPosition >= visibleIndices.size) return
            val realIndex = visibleIndices[displayPosition]
            _messages[realIndex] = _messages[realIndex].copy(content = newContent)
        }
        syncCache()
        messagesLiveData.postValue(_messages.toList())
    }

    /**
     * 删除指定显示位置的消息（position 是过滤掉 system/tool 后的可见列表下标）
     * 如果被删除的是 AI 消息且其前一条是对应的 user 消息，不联动删除（单独删除）
     */
    fun deleteMessageAt(displayPosition: Int) {
        synchronized(_messages) {
            // 构建可见消息与原始下标的映射
            val visibleIndices = _messages.indices
                .filter { _messages[it].role != "system" && _messages[it].role != "tool" }
            if (displayPosition < 0 || displayPosition >= visibleIndices.size) return
            val realIndex = visibleIndices[displayPosition]
            _messages.removeAt(realIndex)
        }
        syncCache()
        messagesLiveData.postValue(_messages.toList())
    }

    /**
     * 带工具调用的请求循环：AI 返回 tool_call 时执行工具并再次请求，直到返回普通文本
     *
     * 处理策略：
     * - BatchConfirmation（整理操作）：同一轮所有此类操作合并收集，一次性展示给用户确认
     * - NeedConfirmation（删除操作）：逐个弹窗确认
     */
    private suspend fun requestWithTools(
        chatMessages: List<ChatMessage>,
        tools: List<Map<String, Any>>
    ): List<ChatMessage> {
        val currentMessages = chatMessages.toMutableList()
        val newMessages = mutableListOf<ChatMessage>()
        repeat(MAX_TOOL_ROUNDS) {
            val response = requestOpenAiMessage(currentMessages, tools)
            if (response.toolCalls.isNullOrEmpty()) {
                // 返回完整 ChatMessage，保留 reasoningContent
                newMessages.add(response.copy(role = "assistant"))
                return newMessages
            }
            // 追加 assistant message（含 tool_calls；reasoning_content 在序列化时一并回传）
            currentMessages.add(response)
            newMessages.add(response)

            // 先执行所有工具，收集结果
            data class ToolCallResult(
                val toolCallId: String,
                val result: ToolExecuteResult
            )
            val toolCallResults = response.toolCalls.map { toolCall ->
                val args = try {
                    GSON.fromJsonObject<Map<String, Any>>(toolCall.function.arguments)
                        .getOrThrow() ?: emptyMap()
                } catch (_: Exception) {
                    emptyMap<String, Any>()
                }
                ToolCallResult(
                    toolCallId = toolCall.id,
                    result = ToolRouter.execute(toolCall.function.name, args)
                )
            }

            // 收集本轮所有 BatchConfirmation 操作
            val batchItems = toolCallResults.filter { it.result is ToolExecuteResult.BatchConfirmation }

            // 批量确认：同一轮所有可合并操作一次性弹窗
            var batchConfirmed = true
            if (batchItems.isNotEmpty()) {
                val descriptions = batchItems.map { (it.result as ToolExecuteResult.BatchConfirmation).description }
                batchConfirmed = requestBatchConfirmation(descriptions)
            }

            // 处理每个 tool call 的结果
            for ((toolCallId, toolResult) in toolCallResults) {
                val resultJson = when (toolResult) {
                    is ToolExecuteResult.Data -> toolResult.json
                    is ToolExecuteResult.BatchConfirmation -> {
                        if (batchConfirmed) {
                            toolResult.action()
                        } else {
                            """{"cancelled":true,"message":"用户取消了批量操作"}"""
                        }
                    }
                }
                val toolMsg = ChatMessage(
                    role = "tool",
                    content = resultJson,
                    toolCallId = toolCallId
                )
                currentMessages.add(toolMsg)
                newMessages.add(toolMsg)
            }
        }
        newMessages.add(ChatMessage(role = "assistant", content = "工具调用轮次已达上限，请重新提问。"))
        return newMessages
    }

    /**
     * 向 Activity 发起单个确认请求（用于删除等高风险操作），挂起等待用户选择
     */
    private suspend fun requestConfirmation(description: String): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        confirmationLiveData.postValue(ConfirmationRequest(description, deferred))
        return deferred.await()
    }

    /**
     * 向 Activity 发起批量确认请求（用于整理类操作），挂起等待用户选择
     */
    private suspend fun requestBatchConfirmation(descriptions: List<String>): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        batchConfirmationLiveData.postValue(BatchConfirmationRequest(descriptions, deferred))
        return deferred.await()
    }

    /**
     * 调用 OpenAI API，返回完整的 ChatMessage（可能包含 tool_calls）
     */
    private suspend fun requestOpenAiMessage(
        chatMessages: List<ChatMessage>,
        tools: List<Map<String, Any>>? = null
    ): ChatMessage = withContext(Dispatchers.IO) {
        val url = AiConfig.apiUrl
        val apiKey = AiConfig.apiKey
        val model = AiConfig.model

        val messagesJsonList = chatMessages.map { msg ->
            val map = mutableMapOf<String, Any>("role" to msg.role)
            if (msg.role == "tool") {
                map["content"] = msg.content
                msg.toolCallId?.let { map["tool_call_id"] = it }
            } else if (!msg.toolCalls.isNullOrEmpty()) {
                map["content"] = msg.content.ifBlank { "" }
                // 工具调用的 assistant 消息也可能携带 reasoning_content，需一并回传
                if (!msg.reasoningContent.isNullOrBlank()) {
                    map["reasoning_content"] = msg.reasoningContent!!
                }
                map["tool_calls"] = msg.toolCalls.map { tc ->
                    mapOf(
                        "id" to tc.id,
                        "type" to "function",
                        "function" to mapOf(
                            "name" to tc.function.name,
                            "arguments" to tc.function.arguments
                        )
                    )
                }
            } else if (msg.role == "assistant" && !msg.reasoningContent.isNullOrBlank()) {
                // 思维链模式：必须将 reasoning_content 回传给 API
                map["content"] = msg.content
                map["reasoning_content"] = msg.reasoningContent
            } else {
                map["content"] = msg.content
            }
            map
        }

        val requestBodyMap = mutableMapOf<String, Any>(
            "model" to model,
            "messages" to messagesJsonList
        )
        if (!tools.isNullOrEmpty()) {
            requestBodyMap["tools"] = tools
        }

        val jsonBody = GSON.toJson(requestBodyMap)
        val body = jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url(url)
            .post(body)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .build()

        val aiHttpClient = okHttpClient.newBuilder()
            .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        val responseString = aiHttpClient.newCall(request).execute().use { response ->
            val bodyStr = response.body.string()
            if (!response.isSuccessful) {
                throw Exception("HTTP ${response.code}: $bodyStr")
            }
            bodyStr.ifBlank { throw Exception("Empty response body") }
        }

        val jsonObject = GSON.fromJsonObject<Map<String, Any?>>(responseString).getOrNull()
            ?: throw Exception("模型返回了非 JSON 格式的内容")
        val choices = jsonObject["choices"] as? List<*>
        val firstChoice = choices?.firstOrNull() as? Map<*, *>
        val messageMap = firstChoice?.get("message") as? Map<*, *> ?: throw Exception("解析响应失败")

        val content = messageMap["content"] as? String ?: ""
        // 解析思维链内容（DeepSeek R1 等思维模式模型返回）
        val reasoningContent = messageMap["reasoning_content"] as? String
        val toolCallsRaw = messageMap["tool_calls"] as? List<Map<*, *>>

        if (!toolCallsRaw.isNullOrEmpty()) {
            val toolCalls = toolCallsRaw.map { tc ->
                val func = tc["function"] as? Map<*, *>
                ToolCall(
                    id = tc["id"] as? String ?: "",
                    function = FunctionCall(
                        name = func?.get("name") as? String ?: "",
                        arguments = func?.get("arguments") as? String ?: "{}"
                    )
                )
            }
            return@withContext ChatMessage(
                role = "assistant",
                content = content,
                toolCalls = toolCalls,
                reasoningContent = reasoningContent
            )
        }

        return@withContext ChatMessage(
            role = "assistant",
            content = content,
            reasoningContent = reasoningContent
        )
    }

    /**
     * 流式请求方法：使用 SSE 逐块接收 OpenAI 兼容 API 的响应
     * onDelta 和 onReasoningDelta 会在每块到达时回调（可在 UI 线程外调用）
     */
    private suspend fun requestOpenAiMessageStreaming(
        chatMessages: List<ChatMessage>,
        msgId: Long,
        onDelta: (String) -> Unit,
        onReasoningDelta: (String) -> Unit
    ): ChatMessage = withContext(Dispatchers.IO) {
        val url = AiConfig.apiUrl
        val apiKey = AiConfig.apiKey
        val model = AiConfig.model

        val messagesJsonList = chatMessages.map { msg ->
            val map = mutableMapOf<String, Any>("role" to msg.role)
            if (msg.role == "tool") {
                map["content"] = msg.content
                msg.toolCallId?.let { map["tool_call_id"] = it }
            } else if (!msg.toolCalls.isNullOrEmpty()) {
                map["content"] = msg.content.ifBlank { "" }
                if (!msg.reasoningContent.isNullOrBlank()) {
                    map["reasoning_content"] = msg.reasoningContent!!
                }
                map["tool_calls"] = msg.toolCalls.map { tc ->
                    mapOf(
                        "id" to tc.id,
                        "type" to "function",
                        "function" to mapOf(
                            "name" to tc.function.name,
                            "arguments" to tc.function.arguments
                        )
                    )
                }
            } else if (msg.role == "assistant" && !msg.reasoningContent.isNullOrBlank()) {
                map["content"] = msg.content
                map["reasoning_content"] = msg.reasoningContent
            } else {
                map["content"] = msg.content
            }
            map
        }

        val requestBodyMap = mutableMapOf<String, Any>(
            "model" to model,
            "messages" to messagesJsonList,
            "stream" to true
        )

        val jsonBody = GSON.toJson(requestBodyMap)
        val body = jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url(url)
            .post(body)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .build()

        val aiHttpClient = okHttpClient.newBuilder()
            .readTimeout(120, TimeUnit.SECONDS)
            .build()

        val accumulated = StringBuilder()
        val accumulatedReasoning = StringBuilder()

        withContext(Dispatchers.IO) {
            try {
                val response = aiHttpClient.newCall(request).execute()
                val body = response.body ?: throw Exception("响应体为空")
                val reader = body.charStream().buffered()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val l = line ?: continue
                    if (l.startsWith("data: ")) {
                        val data = l.removePrefix("data: ")
                        if (data == "[DONE]") break
                        try {
                            val json = GSON.fromJsonObject<Map<String, Any>>(data).getOrNull() ?: continue
                            val choices = json["choices"] as? List<*>
                            val firstChoice = choices?.firstOrNull() as? Map<*, *> ?: continue
                            val delta = firstChoice["delta"] as? Map<*, *> ?: continue

                            val contentDelta = delta["content"] as? String
                            val reasoningDelta = delta["reasoning_content"] as? String

                            if (contentDelta != null) {
                                accumulated.append(contentDelta)
                                onDelta(contentDelta)
                            }
                            if (reasoningDelta != null) {
                                accumulatedReasoning.append(reasoningDelta)
                                onReasoningDelta(reasoningDelta)
                            }
                        } catch (_: Exception) { }
                    }
                }
            } catch (e: Exception) {
                // on error, return partial content
            }
        }

        return@withContext ChatMessage(
            id = msgId,
            role = "assistant",
            content = accumulated.toString(),
            reasoningContent = accumulatedReasoning.toString().ifBlank { null }
        )
    }

    /**
     * 不带工具调用的简单请求，返回纯文本（用于总结记忆等场景）
     */
    private suspend fun requestOpenAi(chatMessages: List<ChatMessage>): String {
        val response = requestOpenAiMessage(chatMessages, tools = null)
        return response.content.ifBlank { throw Exception("响应内容为空") }
    }

    // =========================================================================
    // 小说角色提取
    // =========================================================================

    /**
     * 角色提取结果
     */
    data class CharacterExtractResult(
        val name: String,
        val description: String = "",
        val personality: String = "",
        val background: String = "",
        val speakingStyle: String = ""
    )

    /**
     * 从小说指定章节中提取角色，结果写入 KnowledgePoint DB
     *
     * @param bookUrl 书籍 URL
     * @param bookName 书籍名称
     * @param chapterIndexes 要提取的章节索引列表（从 0 开始）
     * @param onProgress 进度回调：current,total,phase
     * @param onResult 角色提取完成回调
     */
    suspend fun extractCharacters(
        bookUrl: String,
        bookName: String,
        chapterIndexes: List<Int>,
        onProgress: (current: Int, total: Int, phase: String) -> Unit
    ): List<CharacterExtractResult> = withContext(Dispatchers.IO) {
        val book = ReadBook.book ?: throw Exception("未打开书籍")
        if (chapterIndexes.isEmpty()) throw Exception("请选择至少一个章节")
        val bookNameFinal = bookName.ifBlank { book.name.ifBlank { "未命名小说" } }

        // 1. 读取所有选中的章节内容
        onProgress(0, chapterIndexes.size, "读取章节内容")
        val chunks = mutableListOf<String>()
        for ((idx, ci) in chapterIndexes.withIndex()) {
            onProgress(idx + 1, chapterIndexes.size, "读取第 ${ci + 1} 章")
            val chapter = appDb.bookChapterDao.getChapterList(bookUrl, ci, ci).firstOrNull()
                ?: continue
            val content = io.legado.app.help.book.BookHelp.getContent(book, chapter) ?: ""
            if (content.isNotBlank()) {
                // 截取章节前 4000 字避免超长
                val display = if (content.length > 4000) content.substring(0, 4000) + "\n……（截断）" else content
                val title = chapter.title.ifBlank { "第${ci + 1}章" }
                chunks.add("【$title】\n$display")
            }
        }
        if (chunks.isEmpty()) throw Exception("未能读取到章节内容")

        // 2. 逐段发送粗提炼（每次一批最多 3 段，减少 API 调用次数）
        onProgress(0, chunks.size, "AI 分析中")
        val rawSummaries = mutableListOf<String>()
        val batchSize = 3
        for (batchStart in chunks.indices step batchSize) {
            val batch = chunks.subList(batchStart, minOf(batchStart + batchSize, chunks.size))
            val batchPrompt = buildChunkBatchPrompt(batch, rawSummaries.size + 1, chunks.size)
            onProgress(batchStart + batch.size, chunks.size, "分析第 ${batchStart + 1}-${batchStart + batch.size} 段")
            try {
                val response = requestOpenAi(
                    listOf(
                        ChatMessage(role = "user", content = batchPrompt)
                    )
                )
                rawSummaries.add(response)
            } catch (e: Exception) {
                // 跳过失败的批次，不污染合并输入
            }
        }

        // 3. 合并 + 提取角色
        onProgress(0, 0, "正在合并提取角色")
        val mergePrompt = buildMergePrompt(rawSummaries)
        val mergedResponse = try {
            requestOpenAi(
                listOf(
                    ChatMessage(role = "user", content = mergePrompt)
                )
            )
        } catch (e: Exception) {
            throw Exception("合并提取失败：${e.message}")
        }

        // 4. JSON 解析
        val roles = parseCharacterJson(mergedResponse)

        // 5. 写入 DB
        if (roles.isNotEmpty()) {
            for (role in roles) {
                val content = buildCharacterContent(role)
                appDb.knowledgePointDao.insert(
                    KnowledgePoint(
                        title = role.name,
                        content = content,
                        tags = bookNameFinal,
                        category = "character",
                        subCategory = "novel-character",
                        novelName = bookNameFinal,
                        sortOrder = 0,
                        createTime = System.currentTimeMillis(),
                        updateTime = System.currentTimeMillis()
                    )
                )
            }
        }
        return@withContext roles
    }

    /**
     * 批次粗提炼提示词（可从 Whisnya 的 buildNovelChunkPrompt 改造而来）
     */
    private fun buildChunkBatchPrompt(
        chunks: List<String>,
        startIndex: Int,
        total: Int
    ): String {
        val batchText = chunks.joinToString("\n\n---\n\n")
        return """请阅读以下小说片段（第 ${startIndex}-${startIndex + chunks.size - 1} 段 / 共 $total 段），并提炼信息。

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

    /**
     * 合并+提取角色提示词（从 Whisnya 的 buildNovelMergePrompt 改造而来）
     */
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

    /**
     * 从角色提取 JSON 中解析结果
     */
    private fun parseCharacterJson(json: String): List<CharacterExtractResult> {
        var text = json.trim()
        // 剥 Markdown 代码块 fence
        if (text.startsWith("```")) {
            text = text.replaceFirst(Regex("^```(?:json)?\\s*"), "")
                .replaceFirst(Regex("\\s*```$"), "")
        }
        // 尝试提取顶层 JSON 对象
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

    /**
     * 格式化为角色卡内容（KnowledgePoint.content）
     */
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

    /**
     * 拉取供应商提供的模型列表（调用 /models 接口）
     * 返回模型 ID 列表
     */
    suspend fun fetchModels(apiUrl: String, apiKey: String): List<String> = withContext(Dispatchers.IO) {
        val triedUrls = linkedSetOf(
            normalizeApiUrl(apiUrl, "models"),
            buildModelsUrlLegacy(apiUrl)
        )

        var lastError: Exception? = null
        for (modelsUrl in triedUrls) {
            try {
                val request = Request.Builder()
                    .url(modelsUrl)
                    .get()
                    .addHeader("Authorization", "Bearer $apiKey")
                    .build()
                val responseString = okHttpClient.newCall(request).execute().use { response ->
                    val bodyStr = response.body.string()
                    if (!response.isSuccessful) throw Exception("HTTP ${response.code}: $bodyStr")
                    bodyStr
                }
                val ids = parseModelIds(responseString)
                if (ids.isNotEmpty()) {
                    return@withContext ids
                }
            } catch (e: Exception) {
                lastError = e
            }
        }

        throw lastError ?: Exception("未获取到任何模型")
    }

    /**
     * 测试模型是否可用：发送一条 "hi" 消息，成功收到回复则可用
     */
    suspend fun testModel(apiUrl: String, apiKey: String, model: String): String = withContext(Dispatchers.IO) {
        val testMessages = listOf(
            ChatMessage(role = "user", content = "hi")
        )
        val messagesJsonList = testMessages.map { msg ->
            mapOf("role" to msg.role, "content" to msg.content)
        }
        val requestBodyMap = mapOf(
            "model" to model,
            "messages" to messagesJsonList,
            "max_tokens" to 256
        )
        val jsonBody = GSON.toJson(requestBodyMap)
        val body = jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType())

        // 先用原始 URL，失败后用规范化 URL
        val triedUrls = linkedSetOf(
            apiUrl.trim().trimEnd('/'),
            normalizeApiUrl(apiUrl, "chat/completions")
        )

        var lastError: Exception? = null
        for (url in triedUrls) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .post(body)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .build()
                val responseString = okHttpClient.newCall(request).execute().use { response ->
                    val bodyStr = response.body.string()
                    if (!response.isSuccessful) throw Exception("HTTP ${response.code}: $bodyStr")
                    bodyStr
                }
                // 使用 Map<String, Any?> 安全解析，避免 null 强转崩溃
                val raw = GSON.fromJsonObject<Map<String, Any?>>(responseString).getOrNull()
                    ?: throw Exception("模型返回了非 JSON 格式的内容")
                val choices = raw["choices"] as? List<*>
                val first = choices?.firstOrNull() as? Map<*, *>
                val message = first?.get("message") as? Map<*, *>
                val content = message?.get("content") as? String
                return@withContext content ?: "(无回复)"
            } catch (e: Exception) {
                lastError = e
            }
        }

        throw lastError ?: Exception("测试失败")
    }

    private fun parseModelIds(responseString: String): List<String> {
        val modelList = GSON.fromJsonObject<ModelList>(responseString).getOrNull()
        val ids = if (modelList != null) {
            modelList.data.mapNotNull { it.id }
        } else {
            GSON.fromJsonObject<Map<String, Any?>>(responseString).getOrNull()
                ?.let { raw ->
                    (raw["data"] as? List<*>)?.mapNotNull { item ->
                        (item as? Map<*, *>)?.get("id") as? String
                    }
                }.orEmpty()
        }
        return ids.sorted()
    }

    /**
     * 旧版 /models URL 推断逻辑，保留作兼容回退。
     */
    private fun buildModelsUrlLegacy(chatUrl: String): String {
        return try {
            val normalized = chatUrl.trimEnd('/')
            when {
                normalized.endsWith("/chat/completions", ignoreCase = true) ->
                    normalized.dropLast("/chat/completions".length) + "/models"
                normalized.endsWith("/completions", ignoreCase = true) ->
                    normalized.dropLast("/completions".length).substringBeforeLast('/') + "/models"
                else ->
                    normalized.substringBeforeLast('/') + "/models"
            }
        } catch (e: Exception) {
            chatUrl.substringBeforeLast("/chat") + "/models"
        }
    }

    /**
     * 规范化 API URL，自动补全指定后缀。
     * 支持的输入格式：
     *   - `https://api.openai.com/v1`                          → 补 `/chat/completions` 或 `/models`
     *   - `https://api.openai.com/v1/`                         → 同上（去尾斜杠后再补）
     *   - `https://api.openai.com/v1/chat/completions`         → 替换为对应 /models
     *   - `https://api.openai.com/v1/models`                   → 替换为对应 /chat/completions
     *   - `https://example.com/manifest/v1`                    → 补 `/chat/completions` 或 `/models`
     *   - `https://example.com/manifest/v1/chat/completions`   → 替换为对应 /models
     * 后缀由 [appendPath] 决定，传 "chat/completions" 或 "models"。
     */
    private fun normalizeApiUrl(apiUrl: String, appendPath: String): String {
        return try {
            val normalized = apiUrl.trim().trimEnd('/')
            val target = appendPath.trimStart('/')
            when {
                // 已是目标路径
                normalized.endsWith("/$target", ignoreCase = true) -> normalized
                // 是 chat/completions，要 models
                appendPath == "models" && normalized.endsWith("/chat/completions", ignoreCase = true) ->
                    normalized.removeSuffix("/chat/completions") + "/models"
                // 是 models，要 chat/completions
                appendPath == "chat/completions" && normalized.endsWith("/models", ignoreCase = true) ->
                    normalized.removeSuffix("/models") + "/chat/completions"
                // 旧 /completions（非 chat）兜底
                normalized.endsWith("/completions", ignoreCase = true) && target == "models" ->
                    normalized.substringBeforeLast('/') + "/models"
                // 否则当作 base URL，拼接目标路径
                else -> "$normalized/$target"
            }
        } catch (e: Exception) {
            // 异常兜底：原 URL + 目标路径
            "${apiUrl.trim().trimEnd('/')}/${appendPath.trimStart('/')}"
        }
    }
}

data class ChatMessage(
    val id: Long = System.nanoTime(),
    val role: String,
    val content: String = "",
    val references: List<ReferenceItem>? = null,
    val toolCallId: String? = null,
    val toolCalls: List<ToolCall>? = null,
    val reasoningContent: String? = null,
    val isStreaming: Boolean = false
)

data class ToolCall(
    val id: String,
    val function: FunctionCall
)

data class FunctionCall(
    val name: String,
    val arguments: String
)

/**
 * 引用项——记录用户在消息中通过 @ 引用的参考信息
 * type: "chapter" / "knowledge" / "prompt"
 */
data class ReferenceItem(
    val type: String,
    val title: String,
    val id: Long? = null,
    val bookUrl: String? = null,
    val chapterIndex: Int? = null
)

data class ConfirmationRequest(
    val description: String,
    val deferred: CompletableDeferred<Boolean>
)

/**
 * 批量确认请求：包含多个待确认操作的描述列表，一次性展示给用户
 */
data class BatchConfirmationRequest(
    val descriptions: List<String>,
    val deferred: CompletableDeferred<Boolean>
)

/** 应用级内存缓存，持久化跨 Activity 周期的聊天记录 */
object AiChatCache {
    data class State(
        val bookUrl: String = "",
        val chapterIndex: Int = -1,
        val messages: List<ChatMessage> = emptyList()
    )

    @Volatile
    var state: State = State()
}

/**
 * /models 接口响应的标准 OpenAI 结构
 * GSON 默认忽略未知字段（如 "object"），只需声明需要的 data 字段
 */
data class ModelList(
    val data: List<ModelInfo> = emptyList()
)

data class ModelInfo(
    val id: String? = null,
    val `object`: String? = null,
    val owned_by: String? = null,
    val created: Any? = null
)

/**
 * chat/completions 响应结构（用于测试模型可用性）
 */
data class ChatCompletion(
    val id: String? = null,
    val `object`: String? = null,
    val model: String? = null,
    val choices: List<ChatChoice> = emptyList(),
    val usage: Any? = null
)

data class ChatChoice(
    val index: Int? = null,
    val message: ChatMessageContent? = null,
    val finish_reason: String? = null
)

data class ChatMessageContent(
    val role: String? = null,
    val content: String? = null,
    val reasoning_content: String? = null,
    val tool_calls: List<Any>? = null
)
