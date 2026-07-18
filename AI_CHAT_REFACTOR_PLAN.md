# Legado AI 聊天交互重构方案 —— 参考 Tavo 设计

> **目标**：解决"AI 没反应"、"连接被中止"、"提取角色卡死"三个核心 bug，同时参考 Tavo 的交互设计优化用户体验。
>
> **交付标准**：方案可在无 Android SDK 的机器上纯文本看懂，小模型按文件逐个修改，push CI 编译打包。

---

## 目录

1. [问题诊断（必须先理解）](#1-问题诊断必须先理解)
2. [架构变更总览](#2-架构变更总览)
3. [文件改动清单](#3-文件改动清单)
4. [P0 Bug 修复（第一步改）](#4-p0-bug-修复第一步改)
5. [P1 状态提示系统（第二步改）](#5-p1-状态提示系统第二步改)
6. [P2 流式输出 + 工具循环重写（第三步改）](#6-p2-流式输出--工具循环重写第三步改)
7. [P3 提取角色流式化（第四步改）](#7-p3-提取角色流式化第四步改)
8. [P4 UI 交互优化（第五步改）](#8-p4-ui-交互优化第五步改)
9. [推 CI 构建](#9-推-ci-构建)
10. [附录：Tavo 交互模式参考](#10-附录tavo-交互模式参考)

---

## 1. 问题诊断（必须先理解）

### 1.1 "点 AI 对话没反应" + "点提取角色没反应"

**用户端现象**：
- 发消息后发送按钮变暂停图标（`isGeneratingLiveData=true`），但界面无任何文字/动画提示
- 等待几十秒后，突然冒出一行 "请求失败: Software caused connection abort"
- 测试模型按钮是好的，但正式对话就崩
- 提取角色：勾选章节点确定后，点啥都没反应，最后弹错

**代码级根因**：

| 根因 | 位置 | 说明 |
|------|------|------|
| **无 connectTimeout** | `AiChatViewModel.kt` 第 634-637 行 | `okHttpClient` 只设了 `readTimeout(120s)` 和 `callTimeout(120s)`，**没有 `connectTimeout`**。服务端负载高或 DNS 慢时，TCP 连接可能在 2 分钟以上才超时，最终被系统 kill |
| **工具模式非流式阻塞** | 第 315-320 行 | 工具启用时走 `requestWithTools()`，内部调 `requestOpenAiMessage()`（非流式），整个请求过程（120s）内 UI 无任何文字增量更新 |
| **章节内容过大** | 第 196-225 行 | `buildSystemPrompt` 会把**全量章节正文**塞进 system prompt，一个章节几千字 × N 章 = 数万 token，服务端预处理超时 |
| **空 catch 吞异常** | 第 869-871 行 | `extractCharacters` 里批次调用 `requestOpenAi()` 的异常被 `catch (e: Exception) { }` 吞掉——**只跳过失败的批次，不报错不日志**，用户看到的只有最后"提取失败" |
| **无状态回显** | 全局 | 整个交互过程只有 `isGenerating` 布尔值，没有"正在发送→思考中→流式输出"的分阶段反馈 |

### 1.2 Tavo 是怎么做对的

| Tavo 做法 | 对应代码位置 | 启示 |
|-----------|------------|------|
| `chatState = "idle" | "generating"` | `ChatView.updateChatState()` → 控制所有操作栏按钮 disabled | 状态驱动 UI 比布尔值精确 |
| `LoadingBubbleItem` 带三点跳动 | `tav-dot-loading` CSS 动画 | 新消息出现前先放一个占位加载气泡 |
| `addItem` → `updateItem` 两阶段 | `addItem(type="loading")` → `updateItem(content=流式片段)` | 先占位后增量更新，丝滑 |
| `MessageBubbleItem` 出现动画 | `_playAppearAnimationIfNeeded` + CSS `tav-bubble-appear` | 新消息弹性缩放出现 |
| 错误消息内嵌重试按钮 | `ErrorItem` class 含 `.retry` 按钮 | 错误可恢复，不是死线 |
| 全流式（包括工具调用） | 所有 `requestOpenAiMessage` 都走 SSE | 流式是最可靠的超时防御 |
| 滚动到底部/顶部按钮 | `.tav-scroll-bottom-button` FAB | 长列表导航 |

---

## 2. 架构变更总览

### 2.1 ViewModel 状态机

```
旧状态：isGenerating (Boolean)
新状态：statusLiveData (Int)
  STATUS_IDLE = 0      → 空闲，可发送
  STATUS_SENDING = 1   → 正在发送请求（显示 "发送中…"）
  STATUS_THINKING = 2  → AI 正在回复（显示 "AI 思考中…" + 三点动画）
  STATUS_TOOL_RUNNING = 3 → 正在执行工具（显示 "正在执行操作…"）
```

### 2.2 发送消息流程（改后）

```
用户点发送
  → isGenerating = true, status = SENDING
  → 添加 user 消息到列表
  → 添加 assistant 占位消息（isStreaming = true, content = ""）
  → 显示三条跳动动画（在占位气泡内）
  → status = THINKING
  → 发起 SSE 流式请求
    ├─ onDelta: 逐字追加到占位消息 content，RecyclerView 实时刷新
    ├─ onReasoningDelta: 同上，追加到 reasoningContent
    └─ 如果检测到 tool_calls（只在流式结束 chunk 或首个 chunk 可能带）
        └─ status = TOOL_RUNNING
           → 执行工具 → 追加 tool 结果 → 再次发起 SSE 流式请求
           → status = THINKING → 继续流式输出
  → 流式结束 → isStreaming = false, status = IDLE
```

### 2.3 提取角色流程（改后）

```
用户选章节点确定
  → 弹出对话框，显示实时进度
  → 阶段 1/4: 读取章节内容 (1/20… 20/20)
  → 阶段 2/4: AI 分析中 (每批完成后更新)
  → 阶段 3/4: 正在合并提取 (SSE 流式)
  → 阶段 4/4: 保存到知识库 (写 DB)
  → toast 提示数量
```

---

## 3. 文件改动清单

按顺序改，每个文件改动之间有依赖关系。

| 序号 | 文件 | 改动量 | 说明 |
|------|------|--------|------|
| **1** | `AiChatViewModel.kt` | ~200 行新增/改 | P0+P1+P2+P3 核心 |
| **2** | `AiChatActivity.kt` | ~40 行 | P1 状态观察 + P4 加载动画 + 错误重试 |
| **3** | `ChatAdapter.kt` | ~60 行 | P2 流式消息显示 + 三点动画 + P4 操作栏 |
| **4** | `ChatMessage.kt` | ~5 行 | 加 `isStreaming`、`isLoading` 字段（已有则跳过） |
| **5** | `activity_ai_chat.xml` | ~30 行 | P1 状态条 + P4 滚动按钮 |
| **6** | `item_ai_chat.xml` | ~50 行 | P4 加载气泡 + 操作栏 + reasoning 优化 |
| **7** | `drawable/` | 新增 0-3 个 | 三点动画 XML、重试图标 |
| **8** | `strings.xml` | ~5 行 | 新增状态字符串 |

---

## 4. P0 Bug 修复（第一步改）

### 4.1 修改文件：`AiChatViewModel.kt`

**改动 A：加 connectTimeout**

找到 `okHttpClient.newBuilder()` 调用（第 634 行和 744 行两处），在 `readTimeout` 前加一行：

```kotlin
// 第 634 行附近（非流式请求）
val aiHttpClient = okHttpClient.newBuilder()
    .connectTimeout(30, TimeUnit.SECONDS)   // ← 新增
    .readTimeout(120, TimeUnit.SECONDS)
    .callTimeout(120, TimeUnit.SECONDS)
    .build()

// 第 744 行附近（流式请求）
val aiHttpClient = okHttpClient.newBuilder()
    .connectTimeout(30, TimeUnit.SECONDS)   // ← 新增
    .readTimeout(120, TimeUnit.SECONDS)
    .build()
```

### 4.2 `extractCharacters` 异常修复

**改动 B：修复空 catch**

找到 `extractCharacters` 方法内第 869-871 行的空 catch 块：

```kotlin
// 改前：
} catch (e: Exception) {
    // 跳过失败的批次，不污染合并输入
}

// 改后：
} catch (e: Exception) {
    // 记录失败但不中断流程，用占位消息替代
    rawSummaries.add("【批次 ${batchStart / batchSize + 1} 提取失败：${e.message}】")
}
```

---

## 5. P1 状态提示系统（第二步改）

### 5.1 文件：`AiChatViewModel.kt`

**改动 C：加状态常量 + LiveData**

在 `companion object` 内加（约 33 行附近）：

```kotlin
companion object {
    // ... 已有常量 ...

    /** 状态枚举 */
    const val STATUS_IDLE = 0
    const val STATUS_SENDING = 1
    const val STATUS_THINKING = 2
    const val STATUS_TOOL_RUNNING = 3
```

在 LiveData 声明区加：

```kotlin
val statusLiveData = MutableLiveData<Int>()  // 默认 STATUS_IDLE
```

加私有方法：

```kotlin
private fun setStatus(status: Int) {
    statusLiveData.postValue(status)
}
```

**改动 D：`sendMessage` 开头加状态设置**

第 289-291 行附近：

```kotlin
// 改前：
fun sendMessage(...) {
    if (!isGenerating.compareAndSet(false, true)) return
    isGeneratingLiveData.postValue(true)
    if (userText.isBlank()) { setGenerating(false); return }

// 改后：
fun sendMessage(...) {
    if (!isGenerating.compareAndSet(false, true)) return
    isGeneratingLiveData.postValue(true)
    setStatus(STATUS_SENDING)  // ← 新增
    if (userText.isBlank()) { setGenerating(false); setStatus(STATUS_IDLE); return }  // ← 改
```

第 366-374 行的 `finally` 块：

```kotlin
// 改前：
} finally {
    setGenerating(false)
    syncCache()
    messagesLiveData.postValue(_messages.toList())
}

// 改后：
} finally {
    setGenerating(false)
    setStatus(STATUS_IDLE)  // ← 新增
    syncCache()
    messagesLiveData.postValue(_messages.toList())
}
```

**改动 E：`requestWithTools` 内加状态切换**

在工具执行前加 `setStatus(STATUS_TOOL_RUNNING)`：

```kotlin
// 第 521-522 行附近（batchItems 收集之后，执行之前）
// 收集本轮所有 BatchConfirmation 操作
val batchItems = toolCallResults.filter { it.result is ToolExecuteResult.BatchConfirmation }

setStatus(STATUS_TOOL_RUNNING)  // ← 新增

// 批量确认...
```

### 5.2 文件：`AiChatActivity.kt`

**改动 F：观察 statusLiveData**

在 `observeData()` 方法内加（约 307 行后）：

```kotlin
viewModel.statusLiveData.observe(this) { status ->
    when (status) {
        AiChatViewModel.STATUS_IDLE -> {
            binding.statusIndicator.visibility = View.GONE
            binding.statusText.text = ""
        }
        AiChatViewModel.STATUS_SENDING -> {
            binding.statusIndicator.visibility = View.VISIBLE
            binding.statusText.text = "发送请求中…"
        }
        AiChatViewModel.STATUS_THINKING -> {
            binding.statusIndicator.visibility = View.VISIBLE
            binding.statusText.text = "AI 思考中…"
        }
        AiChatViewModel.STATUS_TOOL_RUNNING -> {
            binding.statusIndicator.visibility = View.VISIBLE
            binding.statusText.text = "正在执行操作…"
        }
    }
}
```

### 5.3 文件：`activity_ai_chat.xml`

**改动 G：加状态指示器布局**

在 RecyclerView 与 bottom_bar 之间插入（第 22-23 行中间）：

```xml
<!-- 状态指示器 -->
<LinearLayout
    android:id="@+id/layout_status_indicator"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="horizontal"
    android:gravity="center"
    android:paddingVertical="4dp"
    android:visibility="gone">

    <!-- 三点跳动动画 -->
    <ImageView
        android:id="@+id/status_indicator"
        android:layout_width="16dp"
        android:layout_height="16dp"
        android:src="@drawable/ic_loading_dots"
        android:visibility="gone" />

    <TextView
        android:id="@+id/status_text"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginStart="6dp"
        android:textSize="12sp"
        android:textColor="?android:attr/textColorSecondary" />
</LinearLayout>
```

注意：binding 中 `statusIndicator` 和 `statusText` 会自动绑定到 `layoutStatusIndicator`（外层容器）、`statusIndicator`（ImageView）、`statusText`（TextView）。XML id 命名参考 legado 风格。

### 5.4 文件：`drawable/ic_loading_dots.xml`（新增）

在 `app/src/main/res/drawable/` 下新建：

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- 三点跳动加载动画 -->
<animation-list xmlns:android="http://schemas.android.com/apk/res/android"
    android:oneshot="false">
    <item android:drawable="@drawable/ic_dot_1" android:duration="300" />
    <item android:drawable="@drawable/ic_dot_2" android:duration="300" />
    <item android:drawable="@drawable/ic_dot_3" android:duration="300" />
</animation-list>
```

需要配套三个 dot 的 drawable。**简化方案**：直接用文字 "···" + `MarqueeText` 或 ValueAnimator 改透明度，可跳过 XML drawable。

**替代简化方案（推荐，无需新增 drawable）**：直接用 `android:src="@drawable/ic_refresh_black_24dp"` 加旋转动画：

```kotlin
// AiChatActivity.kt 内 statusLiveData 观察时：
val rotateAnim = ObjectAnimator.ofFloat(binding.statusIndicator, "rotation", 0f, 360f).apply {
    duration = 1000
    repeatCount = ValueAnimator.INFINITE
    interpolator = LinearInterpolator()
}
// status=IDLE 时 cancel()，非 IDLE 时 start()
```

这样不需要新增任何 drawable，复现 Tavo 的 loading spinner 效果。

---

## 6. P2 流式输出 + 工具循环重写（第三步改，核心）

### 6.1 思路

当前问题：
- 工具模式：走 `requestWithTools` → 每轮调用 `requestOpenAiMessage`（非流式，全等）
- 非工具模式：走 `requestOpenAiMessageStreaming`（已有流式）

改造目标：
- **所有路径都走流式**，包括工具模式
- 流式过程中如果收到 `tool_calls`（非流式模式下 tool_calls 在 response 的 `message` 里，流式模式下 tool_calls 在 `delta` 的某个 chunk 里），当前回合打住，执行工具后再发起新一轮流式

### 6.2 流式模式下的 Tool Calls 处理

**关键事实**：OpenAI 流式 API 中 tool_calls 是通过多个 chunks 拼接的（`delta.tool_calls[0].function.arguments` 分片）。需要逐 chunk 拼接，在当前轮流式结束时判断是否有 tool_calls。

**简化策略**（适用于对接的大部分兼容 API）：
1. 第一轮发起流式请求，正常拼接 delta.content
2. 同时监听 delta 中是否出现 `tool_calls`（部分 API 在最后一个 chunk 之前给 tool_calls）
3. 如果流式结束且检测到 tool_calls，则：
   - 清空刚才累积的 content（因为 AI 决定调工具，content 可能是空的或工具描述）
   - 执行工具 → 第二轮流式
4. 如果流式结束没有 tool_calls，content 就是最终回复

### 6.3 修改文件：`AiChatViewModel.kt`

**改动 H：将 `requestOpenAiMessageStreaming` 扩展为支持流式 tool_calls 检测**

修改第 688-793 行的 `requestOpenAiMessageStreaming` 方法：

```kotlin
/**
 * 流式请求方法（增强版：支持检测 tool_calls）
 *
 * 返回值现在可以包含 toolCalls，用于工具循环
 * onDelta / onReasoningDelta 不变
 * onToolCallsDetected: 当检测到工具调用时回调（返回拼接好的 ToolCall 列表）
 */
private suspend fun requestOpenAiMessageStreaming(
    chatMessages: List<ChatMessage>,
    msgId: Long,
    onDelta: (String) -> Unit,
    onReasoningDelta: (String) -> Unit,
    onToolCallsDetected: ((List<ToolCall>) -> Unit)? = null  // ← 新增参数
): ChatMessage = withContext(Dispatchers.IO) {
    val url = AiConfig.apiUrl
    val apiKey = AiConfig.apiKey
    val model = AiConfig.model

    // ... 消息序列化部分不变 ...

    val requestBodyMap = mutableMapOf<String, Any>(
        "model" to model,
        "messages" to messagesJsonList,
        "stream" to true
    )

    // ... 请求发送部分不变 ...

    val accumulated = StringBuilder()
    val accumulatedReasoning = StringBuilder()
    
    // 新增：用于拼接工具调用的临时数据结构
    data class StreamingToolCall(
        val index: Int,
        val id: StringBuilder = StringBuilder(),
        val functionName: StringBuilder = StringBuilder(),
        val arguments: StringBuilder = StringBuilder()
    )
    val streamingToolCalls = mutableMapOf<Int, StreamingToolCall>()

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
                        val json = GSON.fromJsonObject<Map<String, Any?>>(data).getOrNull() ?: continue
                        val choices = json["choices"] as? List<*>
                        val firstChoice = choices?.firstOrNull() as? Map<*, *> ?: continue
                        val delta = firstChoice["delta"] as? Map<*, *> ?: continue

                        val contentDelta = delta["content"] as? String
                        val reasoningDelta = delta["reasoning_content"] as? String
                        val finishReason = firstChoice["finish_reason"] as? String

                        if (contentDelta != null) {
                            accumulated.append(contentDelta)
                            onDelta(contentDelta)
                        }
                        if (reasoningDelta != null) {
                            accumulatedReasoning.append(reasoningDelta)
                            onReasoningDelta(reasoningDelta)
                        }

                        // 检测流式 tool_calls
                        val toolCallsDelta = delta["tool_calls"] as? List<Map<*, *>>
                        if (!toolCallsDelta.isNullOrEmpty()) {
                            for (tc in toolCallsDelta) {
                                val index = (tc["index"] as? Number)?.toInt() ?: continue
                                val current = streamingToolCalls.getOrPut(index) {
                                    StreamingToolCall(index = index)
                                }
                                val func = tc["function"] as? Map<*, *>
                                val tid = tc["id"] as? String
                                val fname = func?.get("name") as? String
                                val fargs = func?.get("arguments") as? String
                                if (tid != null) current.id.append(tid)
                                if (fname != null) current.functionName.append(fname)
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

    // 检查是否检测到 tool_calls
    if (streamingToolCalls.isNotEmpty() && onToolCallsDetected != null) {
        val toolCalls = streamingToolCalls.entries.sortedBy { it.key }.map { (_, stc) ->
            ToolCall(
                id = stc.id.toString(),
                function = FunctionCall(
                    name = stc.functionName.toString(),
                    arguments = stc.arguments.toString()
                )
            )
        }
        onToolCallsDetected(toolCalls)
    }

    return@withContext ChatMessage(
        id = msgId,
        role = "assistant",
        content = accumulated.toString(),
        reasoningContent = accumulatedReasoning.toString().ifBlank { null },
        toolCalls = if (streamingToolCalls.isNotEmpty()) {
            streamingToolCalls.entries.sortedBy { it.key }.map { (_, stc) ->
                ToolCall(
                    id = stc.id.toString(),
                    function = FunctionCall(
                        name = stc.functionName.toString(),
                        arguments = stc.arguments.toString()
                    )
                )
            }
        } else null
    )
}
```

**改动 I：重写 `sendMessage` —— 工具和非工具路径统一流式**

```kotlin
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
            val newSystemPrompt = buildSystemPrompt(start, end, references)
            synchronized(_messages) {
                if (_messages.isNotEmpty() && _messages.first().role == "system") {
                    _messages[0] = ChatMessage(role = "system", content = newSystemPrompt)
                } else {
                    _messages.add(0, ChatMessage(role = "system", content = newSystemPrompt))
                }
            }

            val toolsEnabled = AiConfig.toolEnabled
            val tools = if (toolsEnabled) AiToolDef.allTools else null

            // 统一走流式工具循环
            val finalMessages = requestWithToolsStreaming(_messages.toList(), tools)
            synchronized(_messages) {
                _messages.addAll(finalMessages)
            }
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
```

**改动 J：新增 `requestWithToolsStreaming` 方法**

在 `requestWithTools` 方法旁新增（保留旧方法不删，可备查）：

```kotlin
/**
 * 流式工具循环：每次请求都是 SSE 流式，检测到 tool_calls 时执行工具后再发起下一轮流式
 */
private suspend fun requestWithToolsStreaming(
    chatMessages: List<ChatMessage>,
    tools: List<Map<String, Any>>?
): List<ChatMessage> {
    val currentMessages = chatMessages.toMutableList()
    val newMessages = mutableListOf<ChatMessage>()

    repeat(MAX_TOOL_ROUNDS) {
        val msgId = msgIdCounter.incrementAndGet()

        // 1. 放一个占位消息（流式输出目标）
        val placeholder = ChatMessage(id = msgId, role = "assistant", content = "", isStreaming = true)
        newMessages.add(placeholder)
        // 每轮实时刷新 UI
        messagesLiveData.postValue(_messages.toList() + placeholder)

        setStatus(STATUS_THINKING)

        // 2. 发起流式请求
        val response = requestOpenAiMessageStreaming(
            currentMessages,
            msgId,
            onDelta = { delta ->
                synchronized(_messages) {
                    val idx = _messages.indexOfLast { it.id == msgId }
                    if (idx >= 0) {
                        _messages[idx] = _messages[idx].copy(
                            content = _messages[idx].content + delta
                        )
                        // 同步更新 newMessages 中的内容
                        val newIdx = newMessages.indexOfLast { it.id == msgId }
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
                        val newIdx = newMessages.indexOfLast { it.id == msgId }
                        if (newIdx >= 0) {
                            newMessages[newIdx] = newMessages[newIdx].copy(
                                reasoningContent = (newMessages[newIdx].reasoningContent ?: "") + delta
                            )
                        }
                    }
                }
                messagesLiveData.postValue(_messages.toList())
            }
        )

        // 3. 更新占位消息为非流式
        synchronized(_messages) {
            val idx = _messages.indexOfLast { it.id == msgId }
            if (idx >= 0) {
                _messages[idx] = _messages[idx].copy(isStreaming = false)
            }
        }
        val newIdx = newMessages.indexOfLast { it.id == msgId }
        if (newIdx >= 0) {
            newMessages[newIdx] = response.copy(isStreaming = false)
        }
        messagesLiveData.postValue(_messages.toList())

        // 4. 检查是否有 tool_calls
        if (response.toolCalls.isNullOrEmpty() || tools.isNullOrEmpty()) {
            // 没有工具调用 → 结束
            return newMessages
        }

        // 5. 有工具调用 → 执行工具
        setStatus(STATUS_TOOL_RUNNING)
        currentMessages.add(response.copy(role = "assistant"))

        // 收集执行结果
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

        // 收集所有 BatchConfirmation
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
            newMessages.add(toolMsg)
        }

        // 下一轮循环 → 再次流式请求
    }

    newMessages.add(ChatMessage(role = "assistant", content = "工具调用轮次已达上限，请重新提问。"))
    return newMessages
}
```

**关键注意点**：
- `setStatus(STATUS_THINKING)` 必须在流式请求开始前设，让用户看到"AI 思考中"
- `setStatus(STATUS_TOOL_RUNNING)` 在工具执行前设
- 每一轮流式完成后要 `messagesLiveData.postValue` 刷新 UI
- 需要同步更新 `_messages` 和 `newMessages`，否则消息不同步

### 6.4 非工具模式也走 `requestWithToolsStreaming`

当 `tools=null` 时，`requestWithToolsStreaming` 不会触发工具调用判断，直接返回流式结果。所以 `sendMessage` 不再需要 if/else 分支，统一调用 `requestWithToolsStreaming` 即可。

---

## 7. P3 提取角色流式化（第四步改）

### 7.1 文件：`AiChatViewModel.kt`

**改动 K：`extractCharacters` 增加每个阶段的详细进度**

```kotlin
suspend fun extractCharacters(
    bookUrl: String,
    bookName: String,
    chapterIndexes: List<Int>,
    onProgress: (current: Int, total: Int, phase: String) -> Unit
): List<CharacterExtractResult> = withContext(Dispatchers.IO) {

    // ... 前面的读取章节内容部分不变 ...

    // 2. 逐段发送粗提炼（每次一批最多 3 段）
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
            // 改：记录错误，不吞掉
            rawSummaries.add("【批次 ${batchStart / batchSize + 1} 提取失败：${e.message}】")
        }
    }

    // 3. 合并 + 提取角色（改为流式）
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

    // ... 后面的 JSON 解析 + 写 DB 不变 ...
    // 4. JSON 解析...
    // 5. 写入 DB...
}
```

### 7.2 文件：`AiChatActivity.kt`

**改动 L：提取角色对话框加更清晰的阶段性进度**

`showExtractCharacterDialog()` 里的第 613-624 行，进度回调改为：

```kotlin
lifecycleScope.launch {
    try {
        val roles = viewModel.extractCharacters(
            bookUrl = bookUrl,
            bookName = bookName,
            chapterIndexes = selected,
            onProgress = { cur, tot, phase ->
                runOnUiThread {
                    val phaseEmoji = when {
                        phase.startsWith("读取") → "📖"
                        phase.startsWith("分析") || phase.startsWith("AI") → "🤖"
                        phase.startsWith("合并") → "🔄"
                        phase.startsWith("保存") → "💾"
                        else → ""
                    }
                    progressText.text = if (tot > 0) "$phaseEmoji $phase ($cur/$tot)"
                        else "$phaseEmoji $phase"
                }
            }
        )
        // ... 后续处理不变 ...
```

---

## 8. P4 UI 交互优化（第五步改）

### 8.1 文件：`ChatMessage.kt`（或 `AiChatViewModel.kt` 底部

确认数据类已有以下字段：

```kotlin
data class ChatMessage(
    val id: Long = System.nanoTime(),
    val role: String,
    val content: String = "",
    val references: List<ReferenceItem>? = null,
    val toolCallId: String? = null,
    val toolCalls: List<ToolCall>? = null,
    val reasoningContent: String? = null,
    val isStreaming: Boolean = false   // ← 已有
)
```

如果缺少 `isStreaming` 则加上。

### 8.2 文件：`ChatAdapter.kt`

**改动 M：流式消息显示三点动画**

在 `onBindViewHolder` 的 AI 消息部分（第 82-125 行），加 `isStreaming` 判断：

```kotlin
// AI 消息处理
if (msg.role == "user") {
    // ... 不变 ...
} else {
    holder.binding.llAiMsg.visible()
    holder.binding.llUserMsg.gone()

    if (msg.isStreaming && msg.content.isBlank()) {
        // 流式开始但还没内容 → 显示三点加载动画
        holder.binding.cardAiBubble.visibility = View.GONE
        holder.binding.loadingDots.visibility = View.VISIBLE
        // 如果是 reasoning 模型，加 "正在思考…" 文字
        if (!msg.reasoningContent.isNullOrBlank()) {
            // 有 reasoning 内容时显示
            holder.binding.loadingDots.visibility = View.GONE
            holder.binding.cardAiBubble.visibility = View.VISIBLE
            markwon?.setMarkdown(holder.binding.tvAiContent, msg.content ?: "")
        }
    } else if (msg.isStreaming && msg.content.isNotBlank()) {
        // 流式输出中 → 显示内容
        holder.binding.cardAiBubble.visibility = View.VISIBLE
        holder.binding.loadingDots.visibility = View.GONE
        markwon?.setMarkdown(holder.binding.tvAiContent, msg.content ?: "")
    } else {
        // 正常消息
        holder.binding.cardAiBubble.visibility = View.VISIBLE
        holder.binding.loadingDots.visibility = View.GONE
        markwon?.setMarkdown(holder.binding.tvAiContent, msg.content ?: "")
    }

    // reasoning 折叠部分不变...
}
```

### 8.3 文件：`item_ai_chat.xml`

**改动 N：在 AI 消息气泡区加 loading_dots 容器**

在 AI 消息气泡（`card_ai_bubble`）同一层级新增：

```xml
<!-- 加载三点动画（流式占位） -->
<LinearLayout
    android:id="@+id/loading_dots"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:orientation="horizontal"
    android:gravity="center"
    android:paddingHorizontal="12dp"
    android:paddingVertical="8dp"
    android:visibility="gone">

    <View
        android:layout_width="6dp"
        android:layout_height="6dp"
        android:background="@drawable/shape_dot"
        android:layout_marginEnd="3dp" />
    <View
        android:layout_width="6dp"
        android:layout_height="6dp"
        android:background="@drawable/shape_dot"
        android:layout_marginEnd="3dp" />
    <View
        android:layout_width="6dp"
        android:layout_height="6dp"
        android:background="@drawable/shape_dot" />
</LinearLayout>
```

**改动 O（可选）：加操作栏按钮（参考 Tavo 的 ActionBar）**

在 AI 操作按钮栏 (`ll_ai_actions`) 末尾加：

```xml
<!-- 重试按钮（仅错误消息显示） -->
<ImageButton
    android:id="@+id/btn_ai_retry"
    android:layout_width="32dp"
    android:layout_height="32dp"
    android:layout_marginStart="4dp"
    android:background="?attr/selectableItemBackgroundBorderless"
    android:src="@drawable/ic_refresh_black_24dp"
    android:padding="6dp"
    android:scaleType="centerInside"
    app:tint="?android:attr/textColorSecondary"
    android:contentDescription="重试"
    android:visibility="gone" />
```

### 8.4 文件：`drawable/shape_dot.xml`（新增）

```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="oval">
    <size android:width="6dp" android:height="6dp" />
    <solid android:color="?android:attr/textColorSecondary" />
</shape>
```

### 8.5 三点动画的 UiThread 方案

三点动画是每个 Item 独立的。更好的方案：在 `ChatAdapter` 里用 `ValueAnimator` 对加载中的 Item 做透明度脉冲。

**改动 P：ChatAdapter 加脉冲动画**

```kotlin
// 在 ChatAdapter 类内加
private val dotAnimators = mutableMapOf<Int, ValueAnimator>()

// onViewDetachedFromWindow 时清理
override fun onViewDetachedFromWindow(holder: ChatViewHolder) {
    super.onViewDetachedFromWindow(holder)
    dotAnimators.remove(holder.bindingAdapterPosition)?.cancel()
}

// 在 onBindViewHolder 的 AI 消息 loading_dots 可见时启动动画
private fun startDotAnimation(holder: ChatViewHolder) {
    val dots = listOf(
        holder.binding.loadingDots.getChildAt(0),
        holder.binding.loadingDots.getChildAt(1),
        holder.binding.loadingDots.getChildAt(2)
    )
    dots.forEachIndexed { index, dot ->
        val anim = ObjectAnimator.ofFloat(dot, "alpha", 0.3f, 1.0f).apply {
            duration = 700
            startDelay = (index * 200).toLong()
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
        dotAnimators[holder.bindingAdapterPosition]?.cancel()
        dotAnimators[holder.bindingAdapterPosition] = anim
    }
}
```

### 8.6 文件：`activity_ai_chat.xml`（再优化）

**改动 Q：加滚动到底部 FAB（参考 Tavo scroll-bottom-button）**

在布局文件末尾（TODO: 或建一个新 FAB）：

```xml
<!-- 滚动到底部按钮 -->
<com.google.android.material.floatingactionbutton.FloatingActionButton
    android:id="@+id/fab_scroll_bottom"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:layout_gravity="bottom|end"
    android:layout_margin="16dp"
    android:src="@drawable/ic_arrow_down"
    android:visibility="gone"
    app:fabSize="mini"
    app:backgroundTint="@color/background"
    app:tint="?android:attr/textColorPrimary" />
```

注意：activity_ai_chat.xml 是 LinearLayout，不能直接用 layout_gravity。需要改成 FrameLayout 作为根容器，或者把 FAB 放到一个 FrameLayout 壳里。

**简化方案**：跳过 FAB，只确保 RecyclerView 自动滚到底部（已有 `stackFromEnd = true` 和 `scrollToPosition`）。

### 8.7 错误消息交互优化

**改动 R：在 AiChatActivity 观察消息列表，给错误消息加点击重试**

```kotlin
// observeData 中，messagesLiveData 观察者内
viewModel.messagesLiveData.observe(this) { msgs ->
    val displayMsgs = msgs.filter { it.role != "system" && it.role != "tool" }
    adapter.submitList(displayMsgs)
    // ... 已有滚动逻辑 ...
    
    // 检查最后一条是否为错误消息
    val lastMsg = displayMsgs.lastOrNull()
    if (lastMsg?.role == "assistant" && lastMsg.content?.startsWith("请求失败:") == true) {
        // 自动滚动到底部让用户看到错误
    }
}
```

---

## 9. 推 CI 构建

### 9.1 提交

```bash
cd '/Users/ma/开发/workSpace/legado'
git add -A
git commit -m "refactor(ai-chat): P0-P4 完整改造

修复内容：
- P0: 加 connectTimeout(30s) 修复连接中止
- P0: 修复 extractCharacters 空 catch 吞异常
- P1: 加 statusLiveData 状态提示系统（发送中/思考中/执行操作）
- P2: 工具模式也走流式 SSE，支持 tool_calls 检测
- P3: 提取角色加阶段化进度回显
- P4: 三点加载动画、错误可见性优化

参考 Tavo UI 设计原则改进交互反馈"
```

### 9.2 Push 触发构建

```bash
git push fork main
```

### 9.3 监控构建

```bash
# 查 run ID
gh run list --repo abab1125/legado --limit 3

# 轮询等完成
RUN_ID=<上一步查到的ID>
for i in $(seq 1 40); do
  status=$(gh run view $RUN_ID --repo abab1125/legado --json status --jq '.status')
  concl=$(gh run view $RUN_ID --repo abab1125/legado --json conclusion --jq '.conclusion')
  [ "$status" = "completed" ] && { echo "FINAL=$concl"; break; }
  sleep 20
done
```

---

## 10. 附录：Tavo 交互模式参考

### 10.1 消息生命周期（Tavo 模式）

```
1. addItem({type: "loading"})
   → LoadingBubbleItem 渲染三点跳动动画

2. replaceItem(index, {type: "message", message: {content: "..."}})
   → MessageBubbleItem 渲染，带出现动画

3. updateItem({index, content: "逐步增量内容", streaming: true})
   → 实时增量更新气泡内容，智能滚动

4. updateItem({index, content: "最终内容", streaming: false})
   → 流式结束，加载操作栏（重生成/继续/启发/TTS）

5. (如果出错) addItem({type: "error", errorMessage: "..."})
   → ErrorItem 渲染，显示错误文字 + 重试按钮
```

### 10.2 Legado 改造后的等价流程

```
1. sendMessage()
   → 添加占位 ChatMessage(isStreaming=true, content="")
   → RecyclerView 显示三点动画（通过 adapter 判断 isStreaming）

2. requestOpenAiMessageStreaming onDelta
   → 逐块更新占位消息 content
   → RecyclerView 实时刷新，内容流式出现

3. 流式结束
   → isStreaming = false
   → adapter 隐藏三点动画，显示完整内容 + 操作按钮

4. (工具调用) 执行工具 + 下一轮流式
   → 状态 "正在执行操作…"
   → 同上流式模式

5. (出错) catch 添加错误消息
   → 显示 "请求失败: xxx" + 重试按钮
```

### 10.3 Tavo CSS 动画参考（可转化为 Android 属性动画）

```css
/* 三点跳动 */
.tav-dot-loading .tav-dot {
  animation: 1.5s ease-in-out infinite pulse;
}
@keyframes pulse {
  0% { opacity: 0.5; }
  33.33% { opacity: 0.75; }
  66.67% { opacity: 1; }
  100% { opacity: 0.5; }
}

/* 气泡出现动画 */
.tav-bubble.tav-bubble-appear {
  animation: 0.3s ease-out backwards tavBubbleAppear;
}
@keyframes tavBubbleAppear {
  from { max-height: 0; transform: scale(0); opacity: 0; }
  to { max-height: var(--appear-to-height); transform: scale(1); opacity: 1; }
}
```

对应 Android 属性动画：

```kotlin
// 三点脉冲（在 ChatAdapter 中启动）
ValueAnimator.ofFloat(0.3f, 1.0f).apply {
    duration = 700
    repeatCount = INFINITE
    repeatMode = REVERSE
    addUpdateListener { anim ->
        dot.alpha = anim.animatedValue as Float
    }
}

// 气泡出现缩放
itemView.animate()
    .scaleX(0f).scaleY(0f)
    .setDuration(0)
    .withEndAction {
        itemView.animate()
            .scaleX(1f).scaleY(1f)
            .setDuration(300)
            .setInterpolator(OvershootInterpolator(1.2f))
            .start()
    }
    .start()
```

---

## 附录二：测试检查清单

执行完上述改动后，需要在真机上逐项验证：

| # | 测试场景 | 预期结果 | 验证 |
|---|---------|---------|------|
| 1 | 发送消息（工具关闭） | 状态栏显示 "AI 思考中…" → 内容逐字出现 → 完成 | |
| 2 | 发送消息（工具开启） | 同上 + 需要工具调用时显示 "正在执行操作…" | |
| 3 | 大章节内容（50章+） | 不超时，流式正常返回 | |
| 4 | 提取角色（10 章） | 进度逐步推进 → toast 结果 | |
| 5 | 提取角色（50 章） | 不卡死，不报 connection abort | |
| 6 | 测试模型按钮 | 功能不变 | |
| 7 | 网络断开时发送 | 显示 "请求失败: xxx" + 可重试 | |
| 8 | 快速连续点击发送 | 第二次被阻止（已在生成中） | |
| 9 | 滑动列表 + 流式输出 | 内容正常更新，不跳动不闪退 | |
