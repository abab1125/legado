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
import io.legado.app.data.entities.KnowledgePoint
import io.legado.app.help.book.BookHelp
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import android.util.LruCache

class AiChatViewModel(application: Application) : BaseViewModel(application) {

    companion object {
        // 工具调用循环上限改为可配置（AiConfig.toolMaxRounds，默认 5），见下方取值。
        private const val MAX_TOOL_ROUNDS_FALLBACK = 5

        /**
         * 阅读场景默认【不暴露】给模型的工具。代码全部保留（ToolRouter/AiToolDef 不动），
         * 仅默认不发送这些 tools schema；以后想恢复，从本集合移除名字即可。
         * 依据：书源/RSS 管理、评分/标记阅读状态、写笔记想法、主题配色、替换规则、备份导出，
         * 在阅读 AI 默认场景不需要，去掉可大幅减少上下文与误调用。
         */
        private val DISABLED_TOOLS_BY_DEFAULT = setOf(
            "get_book_sources", "enable_book_source", "update_book_source_group",
            "delete_book_source", "save_book_source",
            "get_rss_sources", "enable_rss_source", "delete_rss_source", "get_source_groups",
            "rate_book", "mark_book_status", "set_book_note",
            "get_replace_rules", "save_replace_rule", "delete_replace_rule",
            "get_theme_configs", "save_theme_config", "delete_theme_config", "apply_theme_config",
            "manage_webdav", "export_to_obsidian"
        )

        /** 状态枚举：UI 根据此值显示不同提示 */
        const val STATUS_IDLE = 0
        const val STATUS_SENDING = 1
        const val STATUS_THINKING = 2
        const val STATUS_TOOL_RUNNING = 3

        const val VOICE_DESIGN_PROMPT = "请为本章所有角色设计声线，要求：\n" +
            "1. 从章节对话中识别所有说话角色（旁白不算）\n" +
            "2. 为每个角色生成一段中文音色描述（1-4句），覆盖性别与年龄、音色/质感、情绪/语气基调、语速/节奏、人设/腔调中的至少3个维度\n" +
            "3. 用具体、可视化的描述，避免“普通”“正常”等模糊词，避免矛盾特征，不要写混响回声等后期处理\n" +
            "4. 输出格式：**角色名** + 换行 + 声线描述，每个角色之间空一行，不需要标签和使用说明"

        /**
         * 客户端缓存（进程级单例）：章节正文与 system 稳定前缀。
         * key 含 bookUrl，不跨书泄漏。
         */
        private val chapterContentCache = LruCache<String, String>(200)
        private val systemPrefixCache = LruCache<String, String>(16)

        /** 章节正文缓存 key：bookUrl + 章节索引 + 内容版本（tag 存更新时间，正文变更后失效） */
        private fun chapterContentKey(bookUrl: String, chapterIndex: Int, version: Any?): String =
            "$bookUrl#$chapterIndex#${version ?: ""}"

        /** system 前缀缓存 key：稳定前缀只包含书籍元数据，随章节数变化 */
        private fun systemPrefixKey(bookUrl: String?, chapterSize: Int): String =
            "$bookUrl#$chapterSize"

        /** AI 改写章节或正文变更后调用：失效该书前缀缓存 */
        @JvmStatic
        fun invalidateCacheForBook(bookUrl: String) {
            chapterContentCache.evictAll()
            // 章节更新可能同时影响书籍元数据/章节数，直接清空小型前缀缓存。
            systemPrefixCache.evictAll()
        }

        /** 供 ViewModel 内部按当前书籍取前缀缓存 key */
        private fun systemPrefixKeyForCurrent(vm: AiChatViewModel): String {
            val book = vm.currentBookUrl()?.let { appDb.bookDao.getBook(it) }
            val chapterSize = if (book != null) ReadBook.chapterSize else 0
            return systemPrefixKey(book?.bookUrl, chapterSize)
        }
    }

    val messagesLiveData = MutableLiveData<List<ChatMessage>>()
    val wordCountLiveData = MutableLiveData<Int>()
    val isGeneratingLiveData = MutableLiveData<Boolean>()

    /**
     * 缓存命中指标（供后续优化评估）。
     * cacheReadTokens = 本轮 Manifest 透传的 cache_read_tokens（命中 KV 缓存的 token 数）。
     */
    data class CacheHitInfo(
        val cacheReadTokens: Long = 0,
        val inputTokens: Long = 0,
        val cacheCreationTokens: Long = 0
    )
    val cacheHitLiveData = MutableLiveData<CacheHitInfo>()

    /**
     * 工具活动状态（用于灵犀式状态卡：读取章节 N 字 / 写入章节 等）
     * kind: tool_start | tool_end | note
     */
    data class ToolActivity(
        val kind: String,
        val name: String,
        val detail: String = ""
    )
    val toolActivityLiveData = MutableLiveData<ToolActivity>()

    /**
     * 章节正文被 Agent 工具改写后发出的通知（供编辑器自动刷新，免保存标记）
     * 值为 bookUrl，编辑器据此重载当前章节
     */
    val chapterUpdatedLiveData = MutableLiveData<String>()

    /**
     * 可注入的书籍上下文。写作页没有 ReadBook 全局态，需显式设置。
     * 不设置时回退到 ReadBook.book（阅读器场景）。
     */
    private var injectedBookUrl: String? = null
    private var injectedChapterIndex: Int = -1
    private var injectedChapterSize: Int = -1

    fun setBookContext(bookUrl: String?, chapterIndex: Int = -1, chapterSize: Int = -1) {
        injectedBookUrl = bookUrl
        injectedChapterIndex = chapterIndex
        injectedChapterSize = chapterSize
    }

    /** 当前书籍 URL：优先注入值，回退 ReadBook */
    private fun currentBookUrl(): String? = injectedBookUrl ?: ReadBook.book?.bookUrl

    /** 当前章节数：优先注入值，回退 ReadBook */
    private fun currentChapterSize(): Int {
        if (injectedChapterSize >= 0) return injectedChapterSize
        return ReadBook.chapterSize
    }
    val statusLiveData = MutableLiveData<Int>()  // STATUS_IDLE / SENDING / THINKING / TOOL_RUNNING
    val confirmationLiveData = MutableLiveData<ConfirmationRequest?>()
    val batchConfirmationLiveData = MutableLiveData<BatchConfirmationRequest?>()

    private fun setStatus(status: Int) {
        statusLiveData.postValue(status)
    }

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
        val chapterSize = currentChapterSize()
        val clampedStart = start.coerceIn(1, chapterSize.coerceAtLeast(1))
        val clampedEnd = end.coerceIn(1, chapterSize.coerceAtLeast(1))
        val st = minOf(clampedStart, clampedEnd)
        val ed = maxOf(clampedStart, clampedEnd)

        val myVersion = wordCountJobVersion.incrementAndGet()
        execute {
            var totalCount = 0
            val book = appDb.bookDao.getBook(bookUrl) ?: return@execute
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
        val currentBookUrl = currentBookUrl() ?: ""
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

    /**
     * 构建【稳定前缀】——逐字节不变，跨多轮对话命中 Manifest 的 KV 缓存。
     * 只有【不可变】内容才放这里。章节正文、用户想法、引用参考属于【可变状态】，
     * 必须放进动态尾部的 user 消息（见 buildContextBlock），绝不能进前缀。
     */
    private suspend fun buildStablePrefix(): String {
        val key = systemPrefixKeyForCurrent(this)
        systemPrefixCache.get(key)?.let { return it }
        val prefix = withContext(Dispatchers.IO) {
            buildString {
                append("【人设与要求】\n")
                // 注入用户在设置页维护的提示词模板（persona）；为空时给最小兜底，避免人设空白
                val persona = AiConfig.persona.trim()
                if (persona.isNotBlank()) {
                    append(persona)
                    append("\n")
                }
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
                val book = currentBookUrl()?.let { appDb.bookDao.getBook(it) }
                if (book != null) {
                    append("\n\n【当前阅读书籍信息】\n")
                    append("书名：${book.name}\n")
                    if (book.author.isNotBlank()) append("作者：${book.author}\n")
                    val intro = book.getDisplayIntro()
                    if (!intro.isNullOrBlank()) append("简介：$intro\n")
                    if (book.originName.isNotBlank()) append("所属书源：${book.originName}\n")
                    append("bookUrl：${book.bookUrl}\n")
                    val chapterSize = ReadBook.chapterSize
                    if (chapterSize > 0) append("总章节数：$chapterSize\n")
                    val durChapterTitle = book.durChapterTitle
                    if (!durChapterTitle.isNullOrBlank()) append("当前阅读章节：$durChapterTitle（第${book.durChapterIndex + 1}章）\n")
                    // 书籍目录索引：每章 index(0-based) + 标题，便于 AI 自主调用 get_book_content 取正文
                    val chapterList = appDb.bookChapterDao.getChapterList(book.bookUrl, 0, (chapterSize - 1).coerceAtLeast(0))
                    if (chapterList.isNotEmpty()) {
                        append("\n【书籍目录（chapterIndex 从 0 开始，调用 get_book_content 取正文）】\n")
                        for (ch in chapterList) {
                            append("${ch.index}. ${ch.title}\n")
                        }
                    }
                    if (AiConfig.toolEnabled) {
                        append("\n【取章节正文方式】：默认不附带章节正文。当用户问到具体章节内容时，请直接调用 get_book_content(bookUrl=\"${book.bookUrl}\", chapterIndex=对应索引) 获取正文，再归纳回答；不要反问用户是否需要调用。章节索引见上方目录。\n")
                    }
                }
            }
        }
        systemPrefixCache.put(key, prefix)
        return prefix
    }

    /**
     * 构建【易变上下文块】——章节正文、用户想法、引用参考。
     * 随章节范围/正文变化，必须放在 user 消息（动态尾部），不进 system 前缀。
     * 返回 null 表示没有额外上下文（如独立模式）。
     */
    private suspend fun buildContextBlock(
        start: Int, end: Int,
        references: List<ReferenceItem>? = null
    ): String? = withContext(Dispatchers.IO) {
        val unavailable = "（内容不可用）"
        val book = currentBookUrl()?.let { appDb.bookDao.getBook(it) } ?: return@withContext null
        if (start <= 0 || end <= 0) return@withContext null
        val chapterSize = ReadBook.chapterSize
        if (chapterSize <= 0) return@withContext null
        val st = minOf(start, end).coerceIn(1, chapterSize)
        val ed = maxOf(start, end).coerceIn(1, chapterSize)
        val rangeDesc = if (st == ed) "第${st}章" else "第${st}章 ~ 第${ed}章"
        buildString {
            append("\n\n【参考章节内容（$rangeDesc）】\n")
            val chapterList = appDb.bookChapterDao.getChapterList(book.bookUrl, st - 1, ed - 1)
            for (chapter in chapterList) {
                val ckey = chapterContentKey(book.bookUrl, chapter.index, chapter.tag)
                val content = chapterContentCache.get(ckey) ?: run {
                    val c = BookHelp.getContent(book, chapter)
                    c?.also { chapterContentCache.put(ckey, it) }
                } ?: continue
                append("=== ${chapter.title} ===\n")
                append(content)
                append("\n\n")
                val thoughts = appDb.bookThoughtDao.getByChapter(book.name, book.author, chapter.index)
                if (thoughts.isNotEmpty()) {
                    append("【用户在本章的想法（共${thoughts.size}条）】\n")
                    thoughts.forEachIndexed { i, t ->
                        append("${i + 1}. ")
                        if (t.selectedText.isNotBlank()) append("「${t.selectedText.take(300)}」")
                        if (t.thought.isNotBlank()) {
                            if (t.selectedText.isNotBlank()) append(" → ")
                            append(t.thought)
                        }
                        append("\n")
                    }
                    append("\n")
                }
            }
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
                                append("【章节】${ref.title}$unavailable\n")
                            }
                        }
                        "knowledge" -> {
                            if (ref.id != null) {
                                val kp = appDb.knowledgePointDao.getById(ref.id)
                                if (kp != null) append("【知识点：${kp.title}】\n${kp.content}\n")
                                else append("【知识点】${ref.title}$unavailable\n")
                            } else append("【知识点】${ref.title}$unavailable\n")
                        }
                        "prompt" -> {
                            if (ref.id != null) {
                                val wp = appDb.writingPromptDao.getById(ref.id)
                                if (wp != null) append("【提示词：${wp.title}】\n${wp.content}\n")
                                else append("【提示词】${ref.title}$unavailable\n")
                            } else append("【提示词】${ref.title}$unavailable\n")
                        }
                    }
                }
            }
        }.takeIf { it.isNotBlank() }
    }

    /** 兼容旧调用：仅返回稳定前缀 */
    private suspend fun buildSystemPrompt(
        start: Int, end: Int,
        references: List<ReferenceItem>? = null
    ): String = buildStablePrefix()

    fun sendMessage(userText: String, start: Int, end: Int, references: List<ReferenceItem>? = null) {
        if (!isGenerating.compareAndSet(false, true)) return
        isGeneratingLiveData.postValue(true)
        setStatus(STATUS_SENDING)
        if (userText.isBlank()) {
            setGenerating(false)
            setStatus(STATUS_IDLE)
            return
        }

        synchronized(_messages) {
            _messages.add(ChatMessage(role = "user", content = userText, references = references))
        }
        syncCache()
        messagesLiveData.postValue(_messages.toList())

        execute {
            try {
                val stablePrefix = buildStablePrefix()
                synchronized(_messages) {
                    if (_messages.isNotEmpty() && _messages.first().role == "system") {
                        _messages[0] = ChatMessage(role = "system", content = stablePrefix)
                    } else {
                        _messages.add(0, ChatMessage(role = "system", content = stablePrefix))
                    }
                }

                setStatus(STATUS_THINKING)
                // 暴露白名单工具：默认不发送 DISABLED_TOOLS_BY_DEFAULT 中的工具（代码保留，可恢复）
                val tools = if (AiConfig.toolEnabled) {
                    AiToolDef.allTools.filter { (it["function"] as? Map<*, *>)?.get("name") !in DISABLED_TOOLS_BY_DEFAULT }
                } else null
                requestWithToolsStreaming(_messages.toList(), tools)
            } catch (e: Exception) {
                synchronized(_messages) {
                    _messages.add(ChatMessage(role = "assistant", content = "请求失败: ${e.message}"))
                }
            } finally {
                setGenerating(false)
                setStatus(STATUS_IDLE)
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
        val maxRounds = AiConfig.toolMaxRounds
        val recentCalls = mutableListOf<String>() // 重复调用检测
        repeat(maxRounds) { round ->
            val response = requestOpenAiMessage(currentMessages, tools)
            if (response.toolCalls.isNullOrEmpty()) {
                // 返回完整 ChatMessage，保留 reasoningContent
                newMessages.add(response.copy(role = "assistant"))
                return newMessages
            }
            // 重复调用保护：归一化 (name+args) 短窗口内重复则提前终止，避免无意义空转
            val callKeys = response.toolCalls.map { tc ->
                val args = try { GSON.fromJsonObject<Map<String, Any>>(tc.function.arguments).getOrNull() ?: emptyMap() } catch (_: Exception) { emptyMap<String, Any>() }
                tc.function.name + "::" + args.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }
            }
            val duplicate = callKeys.all { key -> recentCalls.count { it == key } >= 2 }
            recentCalls.addAll(callKeys)
            if (recentCalls.size > maxRounds * 2) recentCalls.removeAt(0)
            if (duplicate) {
                // 模型在重复调用相同工具，无进展 → 提前终止，由已有结果回答
                newMessages.add(ChatMessage(role = "assistant", content = "已多次重复调用相同工具，停止工具循环。基于已获取的内容回答："))
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
     * 流式工具循环（统一路径）
     *
     * 每一轮都是 SSE 流式请求：
     * 1. 新建占位消息（isStreaming=true），立刻显示三点动画
     * 2. 发起流式请求，onDelta 实时追加到占位消息
     * 3. 流式结束后检查是否含 tool_calls
     * 4. 有 → 执行工具 → 追加 tool 消息 → 下一轮流式
     * 5. 无 → 完成
     *
     * tools=null 时纯流式输出，不检查工具调用
     */
    private suspend fun requestWithToolsStreaming(
        chatMessages: List<ChatMessage>,
        tools: List<Map<String, Any>>?
    ): List<ChatMessage> {
        val currentMessages = chatMessages.toMutableList()
        val newMessages = mutableListOf<ChatMessage>()
        val maxRounds = AiConfig.toolMaxRounds
        val recentCalls = mutableListOf<String>()

        repeat(maxRounds) { round ->
            val msgId = msgIdCounter.incrementAndGet()

            // 1. 放占位消息，显示三点动画
            val placeholder = ChatMessage(id = msgId, role = "assistant", content = "", isStreaming = true)
            synchronized(_messages) {
                _messages.add(placeholder)
            }
            newMessages.add(placeholder)
            messagesLiveData.postValue(_messages.toList())

            setStatus(STATUS_THINKING)

            // 2. 流式请求
            val response = requestOpenAiMessageStreaming(
                currentMessages, msgId,
                onDelta = { delta ->
                    synchronized(_messages) {
                        val idx = _messages.indexOfLast { it.id == msgId }
                        if (idx >= 0) {
                            _messages[idx] = _messages[idx].copy(
                                content = _messages[idx].content + delta
                            )
                            val newIdx = newMessages.indexOfLast { n -> n.id == msgId }
                            if (newIdx >= 0) {
                                newMessages[newIdx] = newMessages[newIdx].copy(
                                    content = newMessages[newIdx].content + delta
                                )
                            }
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
                            val newIdx = newMessages.indexOfLast { n -> n.id == msgId }
                            if (newIdx >= 0) {
                                newMessages[newIdx] = newMessages[newIdx].copy(
                                    reasoningContent = (newMessages[newIdx].reasoningContent ?: "") + delta
                                )
                            }
                        }
                    }
                    messagesLiveData.postValue(_messages.toList())
                },
                tools = tools
            )

            // 3. 更新占位为非流式；纯工具调用轮（无文本）则移除空气泡，避免残留空气泡
            val hasToolOnly = !response.toolCalls.isNullOrEmpty() && response.content.isBlank() && response.reasoningContent.isNullOrBlank()
            synchronized(_messages) {
                val idx = _messages.indexOfLast { it.id == msgId }
                if (idx >= 0) {
                    if (hasToolOnly) {
                        _messages.removeAt(idx)
                    } else {
                        _messages[idx] = _messages[idx].copy(isStreaming = false)
                    }
                }
            }
            val newIdx = newMessages.indexOfLast { n -> n.id == msgId }
            if (newIdx >= 0) {
                if (hasToolOnly) newMessages.removeAt(newIdx)
                else newMessages[newIdx] = response.copy(isStreaming = false)
            }
            messagesLiveData.postValue(_messages.toList())

            // 4. 没有工具或没有工具调用 → 结束
            if (tools.isNullOrEmpty() || response.toolCalls.isNullOrEmpty()) {
                if (response.content.isBlank() && response.reasoningContent.isNullOrBlank()) {
                    // 非空检查，只在无工具时做
                    if (tools.isNullOrEmpty()) throw Exception("响应内容为空")
                }
                return newMessages
            }

            // 5. 有工具调用 → 执行
            setStatus(STATUS_TOOL_RUNNING)
            // 本轮 get_book_content 调用数（用于进度 1/N）
            val bookContentCalls = response.toolCalls.filter { it.function.name == "get_book_content" }
            val totalReads = bookContentCalls.size
            var doneReads = 0
            var readChars = 0
            // 重复调用保护：归一化 (name+args) 短窗口内重复则提前终止
            val callKeys = response.toolCalls.map { tc ->
                val args = try { GSON.fromJsonObject<Map<String, Any>>(tc.function.arguments).getOrNull() ?: emptyMap() } catch (_: Exception) { emptyMap<String, Any>() }
                tc.function.name + "::" + args.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }
            }
            val duplicate = callKeys.all { key -> recentCalls.count { it == key } >= 2 }
            recentCalls.addAll(callKeys)
            if (recentCalls.size > maxRounds * 2) recentCalls.removeAt(0)
            if (duplicate) {
                // 模型重复调用相同工具，无进展 → 终止循环，直接出最终回答
                newMessages.add(ChatMessage(role = "assistant", content = "已多次重复调用相同工具，停止工具循环。基于已获取的内容回答："))
                return newMessages
            }
            currentMessages.add(response.copy(role = "assistant"))

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
                val result = ToolRouter.execute(toolCall.function.name, args)
                // 进度累计：get_book_content 完成时更新状态卡（字数 + 进度）
                if (toolCall.function.name == "get_book_content") {
                    doneReads++
                    val len = (result as? ToolExecuteResult.Data)?.json
                        ?.let { runCatching { (GSON.fromJsonObject<Map<String, Any>>(it).getOrNull())?.get("data") as? Map<*, *> }.getOrNull() }
                        ?.let { (it?.get("contentLength") as? Number)?.toInt() ?: 0 } ?: 0
                    readChars += len
                    if (totalReads > 1) {
                        AiToolStatusBus.postToolActivity(
                            "tool_progress", "get_book_content",
                            "已读 $readChars 字（${doneReads}/${totalReads}）"
                        )
                    }
                }
                ToolCallResult(
                    toolCallId = toolCall.id,
                    result = result
                )
            }

            // 批量确认
            val batchItems = toolCallResults.filter { it.result is ToolExecuteResult.BatchConfirmation }
            var batchConfirmed = true
            if (batchItems.isNotEmpty()) {
                val descriptions = batchItems.map { (it.result as ToolExecuteResult.BatchConfirmation).description }
                batchConfirmed = requestBatchConfirmation(descriptions)
            }

            // 追加 tool 消息
            for ((toolCallId, toolResult) in toolCallResults) {
                val resultJson = when (toolResult) {
                    is ToolExecuteResult.Data -> toolResult.json
                    is ToolExecuteResult.BatchConfirmation -> {
                        if (batchConfirmed) toolResult.action()
                        else """{"cancelled":true,"message":"用户取消了批量操作"}"""
                    }
                }
                val toolMsg = ChatMessage(role = "tool", content = resultJson, toolCallId = toolCallId)
                currentMessages.add(toolMsg)
                synchronized(_messages) { _messages.add(toolMsg) }
                newMessages.add(toolMsg)
            }
            messagesLiveData.postValue(_messages.toList())

            // 下一轮流式
            setStatus(STATUS_THINKING)
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
        val apiKey = AiConfig.apiKey
        val model = AiConfig.model
        val url = AiConfig.normalizedChatUrl

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
            } else if (msg.role == "system") {
                // 稳定前缀用 content block 标记缓存，Manifest 会透传给 Claude。
                map["content"] = listOf(
                    mapOf(
                        "type" to "text",
                        "text" to msg.content,
                        "cache_control" to mapOf("type" to "ephemeral")
                    )
                )
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
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
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

        // Manifest 透传的缓存 usage，用于后续根据真实命中率继续优化。
        (jsonObject["usage"] as? Map<*, *>)?.let { usage ->
            val details = usage["prompt_tokens_details"] as? Map<*, *>
            val cached = (details?.get("cached_tokens") as? Number)
                ?: (usage["cache_read_tokens"] as? Number)
            val input = (usage["prompt_tokens"] as? Number)
                ?: (usage["input_tokens"] as? Number)
            val creation = (details?.get("cache_creation_tokens") as? Number)
                ?: (usage["cache_creation_tokens"] as? Number)
            if (cached != null || input != null) {
                cacheHitLiveData.postValue(CacheHitInfo(
                    cacheReadTokens = cached?.toLong() ?: 0,
                    inputTokens = input?.toLong() ?: 0,
                    cacheCreationTokens = creation?.toLong() ?: 0
                ))
            }
        }

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
        onReasoningDelta: (String) -> Unit,
        tools: List<Map<String, Any>>? = null
    ): ChatMessage = withContext(Dispatchers.IO) {
        val apiKey = AiConfig.apiKey
        val model = AiConfig.model
        val url = AiConfig.normalizedChatUrl

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
            } else if (msg.role == "system") {
                map["content"] = listOf(
                    mapOf(
                        "type" to "text",
                        "text" to msg.content,
                        "cache_control" to mapOf("type" to "ephemeral")
                    )
                )
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
        if (!tools.isNullOrEmpty()) {
            requestBodyMap["tools"] = tools as Any
        }

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
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()

        // 流式 tool_calls 字段去重拼接：部分 API 会在多个 delta 重复发送完整的
        // name/id（如 "get_book_content" 发两遍），无脑 append 会拼成
        // "get_book_contentget_book_content"，导致工具名匹配失败。
        // 检测与已累积内容的最长后缀重叠，仅 append 新增部分。
        fun appendDedup(builder: StringBuilder, chunk: String) {
            val cur = builder.toString()
            if (cur.endsWith(chunk)) return
            val maxOverlap = if (cur.length < chunk.length) cur.length else chunk.length
            var overlap = 0
            for (k in 1..maxOverlap) {
                if (cur.takeLast(k) == chunk.take(k)) overlap = k
            }
            builder.append(chunk.substring(overlap))
        }

        val accumulated = StringBuilder()
        val accumulatedReasoning = StringBuilder()

        // 用于拼接流式 tool_calls（多个 chunk 分片到达）
        data class StreamingToolCall(
            val index: Int,
            val id: StringBuilder = StringBuilder(),
            val functionName: StringBuilder = StringBuilder(),
            val arguments: StringBuilder = StringBuilder()
        )
        val streamingToolCalls = mutableMapOf<Int, StreamingToolCall>()
        var finishReason: String? = null

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

                            // 记录 finish_reason（最后一个 chunk 才有）
                            val fr = firstChoice["finish_reason"] as? String
                            if (fr != null) finishReason = fr

                            // 流式 usage 通常在最后一个 SSE chunk 的顶层。
                            (json["usage"] as? Map<*, *>)?.let { usage ->
                                val details = usage["prompt_tokens_details"] as? Map<*, *>
                                val cached = (details?.get("cached_tokens") as? Number)
                                    ?: (usage["cache_read_tokens"] as? Number)
                                val input = (usage["prompt_tokens"] as? Number)
                                    ?: (usage["input_tokens"] as? Number)
                                val creation = (details?.get("cache_creation_tokens") as? Number)
                                    ?: (usage["cache_creation_tokens"] as? Number)
                                if (cached != null || input != null) {
                                    cacheHitLiveData.postValue(CacheHitInfo(
                                        cacheReadTokens = cached?.toLong() ?: 0,
                                        inputTokens = input?.toLong() ?: 0,
                                        cacheCreationTokens = creation?.toLong() ?: 0
                                    ))
                                }
                            }

                            // 检测流式 tool_calls
                            val toolCallsDelta = delta["tool_calls"] as? List<Map<*, *>>
                            if (!toolCallsDelta.isNullOrEmpty()) {
                                for (tc in toolCallsDelta) {
                                    val index = (tc["index"] as? Number)?.toInt() ?: continue
                                    val current = streamingToolCalls.getOrPut(index) {
                                        StreamingToolCall(index = index)
                                    }
                                    val tid = tc["id"] as? String
                                    val func = tc["function"] as? Map<*, *>
                                    val fname = func?.get("name") as? String
                                    val fargs = func?.get("arguments") as? String
                                    if (tid != null) appendDedup(current.id, tid)
                                    if (fname != null) appendDedup(current.functionName, fname)
                                    if (fargs != null) current.arguments.append(fargs)
                                }
                            }
                        } catch (_: Exception) { }
                    }
                }
            } catch (e: Exception) {
                // on error, return partial content
            }
        }

        // 拼接 tool_calls（如果有）
        val toolCalls = if (streamingToolCalls.isNotEmpty() || finishReason == "tool_calls") {
            streamingToolCalls.entries.sortedBy { it.key }.map { (_, stc) ->
                ToolCall(
                    id = stc.id.toString(),
                    function = FunctionCall(
                        name = stc.functionName.toString(),
                        arguments = stc.arguments.toString()
                    )
                )
            }.ifEmpty { null }
        } else null

        return@withContext ChatMessage(
            id = msgId,
            role = "assistant",
            content = accumulated.toString(),
            reasoningContent = accumulatedReasoning.toString().ifBlank { null },
            toolCalls = toolCalls
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
        val book = appDb.bookDao.getBook(bookUrl) ?: throw Exception("未找到书籍：$bookUrl")
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
                rawSummaries.add("【批次 ${batchStart / batchSize + 1} 提取失败：${e.message}】")
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
