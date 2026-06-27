# AI 助手 @引用 + 章节插入 设计方案

## 一、需求总览

1. **@引用功能**：AI 对话框输入时，能引用「章节」「知识点」「提示词」三项内容
2. **AI 输出插入为章节**：AI 生成正文后，指定位置插入为新章节，后续章节目录自动重排（index+1）

---

## 二、@引用功能设计

### 2.1 交互方案

**不在输入框监听 `@` 符号实时弹窗，用快捷按钮。**

Ponytail 理由：实时监听 TextWatcher 做弹窗选单，跟多行输入框编辑体验冲突（打字中途弹选单打断），实现复杂坑多。

改为**输入框上方加一排引用按钮**：

```
┌──────────────────────────────────────┐
│  @章节  📝知识点  @提示词  (引用来源) │  ← 新增引用按钮行
├──────────────────────────────────────┤
│  章节: [ 5 ] 至 [ 5 ]  字数: xx      │
├──────────────────────────────────────┤
│  [ 已引用: 第5章 转折 ]              │  ← 引用标签芯片
├──────────────────────────────────────┤
│  [输入框...                  ]  [发送]│
└──────────────────────────────────────┘
```

- 点击「@章节」→ 弹出章节选择弹窗 → 确认后输入框插入 `@第x章:xxx`，上方显示引用芯片
- 点击「📝知识点」→ 弹出知识点选择弹窗 → 插入 `@知识点:xxx`
- 点击「@提示词」→ 弹出提示词选择弹窗 → 插入 `@提示词:xxx`

### 2.2 引用数据模型

发送时解析 `@` 文本，构建引用元数据附加到 ChatMessage：

```kotlin
data class ReferenceItem(
    val type: String,        // "chapter" / "knowledge" / "prompt"
    val title: String,
    val id: Long?,           // knowledge/prompt 的 id
    val bookUrl: String?,    // chapter 的书 url
    val chapterIndex: Int?   // chapter 的索引
)
```

ChatMessage 新增可选字段：
```kotlin
data class ChatMessage(
    val role: String,
    val content: String = "",
    val references: List<ReferenceItem>? = null,  // ← 新增
    val toolCallId: String? = null,
    val toolCalls: List<ToolCall>? = null,
    val reasoningContent: String? = null
)
```

### 2.3 System Prompt 注入

用户发送带引用的消息时，在 `buildSystemPrompt` 中将引用内容注入到【引用信息】区块：

- **@章节** → 读取该章节正文（BookHelp.getContent），注入全文
- **@知识点** → 读取 KnowledgePoint.content
- **@提示词** → 读取 WritingPrompt.content（作为创作指导）

这样 LLM 看到的是具体内容而非 `@第x章` 标签。

### 2.4 改动文件清单

| 文件 | 改动 |
|------|------|
| `activity_ai_chat.xml` | 新增引用按钮行 + 引用芯片行 |
| `AiChatActivity.kt` | 绑定按钮事件、弹窗回调、芯片管理 |
| `AiChatViewModel.kt` | ChatMessage 加 references、buildSystemPrompt 注入引用 |
| `ChapterPickerDialog.kt` | **新增**：章节选择弹窗 |
| `ReferenceChipAdapter.kt` | **新增**：引用芯片 RecyclerView |
| `strings.xml` | 引用相关 string |

---

## 三、AI 输出插入为章节

### 3.1 核心方案：两个新 Tool

在 `AiToolDef.kt` + `ToolRouter.kt` 新增：

**工具1：`insert_chapter_text`**（静默工具，不弹确认）
```kotlin
tool(
    "insert_chapter_text",
    "为当前创作书籍新增章节正文。调用此工具后，AI先将正文暂存。" +
    "然后再用 insert_chapter_at 指定插入位置。",
    required = listOf("chapterContent", "chapterTitle"),
    properties = mapOf(
        "chapterContent" to prop("string", "新增章节的完整正文"),
        "chapterTitle" to prop("string", "章节标题，如第5章 转折"),
    )
)
```

**工具2：`insert_chapter_at`**（批量确认，弹窗确认）
```kotlin
tool(
    "insert_chapter_at",
    "将 insert_chapter_text 缓存的正文插入到指定位置。" +
    "系统自动将后面的原章节 index+1，不覆盖任何内容。",
    required = listOf("insertAfterChapterIndex"),
    properties = mapOf(
        "insertAfterChapterIndex" to prop("integer",
            "插入到哪一章之后（0-based）。插在第5章位置传入4"),
    )
)
```

### 3.2 数据流

```
AI 生成正文
  → tool_call: insert_chapter_text(content, title)
  → ToolRouter 缓存到 ViewModel.tempChapterContent
  → AI 再调 insert_chapter_at(afterIndex=N)  确认位置
  → ToolRouter.BatchConfirmation:
    "将正文《第N章 xxx》插入到第N章之后"

用户确认后：
  → DB 事务：
    1. 查全书章节列表
    2. 从后往前把 afterIndex 之后的章节 index+1
    3. 插入新章节（index = afterIndex + 1）
    4. BookHelp.saveText() 写 .nb 文件
  → 通知目录刷新
```

### 3.3 复用已有 `insertNextChapter` 逻辑

ChapterListFragment 已有先挪后插逻辑，提取为公共函数：

```kotlin
suspend fun insertChapterAfter(
    bookUrl: String,
    afterIndex: Int,
    newChapter: BookChapter
) {
    val chapters = appDb.bookChapterDao.getChapterList(bookUrl)
    chapters.filter { it.index > afterIndex }
        .sortedByDescending { it.index }
        .forEach { appDb.bookChapterDao.update(it.copy(index = it.index + 1)) }
    appDb.bookChapterDao.insert(newChapter)
}
```

### 3.4 Pitfalls

- **P0**：挪 index 必须从后往前（sortedByDescending），否则 UNIQUE(bookUrl,index) 冲突
- **P0**：新章节 baseUrl 必须与当前 book 一致，不然正文文件存错路径
- **P1**：正文不宜通过 tool arguments 传 JSON（可能截断），先用 insert_chapter_text 缓存
- **P1**：插入后需通知目录 Fragment 刷新（通过 ViewModel 事件或广播）

---

## 四、Ponytail 评估

### 4.1 不需要的（YAGNI）

- ❌ 输入框监听 `@` 实时弹窗 → 按钮够用
- ❌ 独立的引用管理页面 → 弹窗复用现有 Dialog 风格
- ❌ 批量章节插入 → 先不做，加个循环的事
- ❌ 插入后自动聚焦到新章节 → 当前够了

### 4.2 已可复用的

- ✅ insertNextChapter 的先挪后插逻辑
- ✅ BatchConfirmation 弹窗机制
- ✅ KnowledgeManageDialog / PromptManageDialog 的布局风格和数据库访问
- ✅ BookHelp.getContent / saveText 正文读写

### 4.3 新增 vs 复用

| 项 | 方式 |
|------|------|
| 章节选择弹窗 | 新写 ChapterPickerDialog（DialogFragment+RecyclerView） |
| 知识点选择弹窗 | 复用 Adapter 包成 Dialog |
| 提示词选择弹窗 | 复用 Adapter 包成 Dialog |
| 章节插入核心 | 从 insertNextChapter 提取公共函数 |
| 引用元数据 | ChatMessage 加 references 字段 |

---

## 五、开发顺序

| Phase | 内容 | 前置依赖 |
|-------|------|---------|
| 1 | Tool: insert_chapter_text + insert_chapter_at + Router | 无（核心链路） |
| 2 | ChatMessage references + buildSystemPrompt 注入 | Phase 1 |
| 3 | UI 引用按钮行 + 三个选择弹窗 + 芯片 | Phase 2 |
| 4 | 端到端测试 + 边界情况 | Phases 1-3 |
