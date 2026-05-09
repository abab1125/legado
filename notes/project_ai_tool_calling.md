---
name: AI Tool Calling 功能完整开发记录
description: 阅读伴侣AI工具调用功能的完整开发过程：Phase 1只读、Phase 2写操作、独立入口、bug修复
type: project
originSessionId: 2df45cdf-b0d1-4097-b9ec-7a4440d052f9
---
## 功能概述

为 legado 阅读 app 的 AI 阅读伴侣添加 OpenAI Function Calling 支持，让 AI 能调用 app 内置方法操作书架、书源、订阅等数据。

**开发时间**：2026/05/08
**分支**：main

---

## 提交记录

| 提交 | 内容 |
|------|------|
| `5609b682d` | Phase 1：只读工具（8个） |
| `234ef9d9e` | Phase 2：写操作工具（6个）+ 用户确认机制 |
| `e36fc3fb6` | 独立AI助手入口 + 写操作线程bug修复 |

---

## Phase 1 — 只读工具

### 新增文件
- `ui/book/read/ai/tool/AiToolDef.kt` — 工具定义层，OpenAI tools JSON Schema 格式
- `ui/book/read/ai/tool/ToolRouter.kt` — 工具执行路由，调用 DAO 层返回精简 JSON

### 修改文件
- `AiChatViewModel.kt` — ChatMessage 扩展 toolCallId/toolCalls；新增 requestOpenAiMessage（返回完整 ChatMessage）；新增 requestWithTools（多轮循环，最多5轮）；sendMessage 按 toolEnabled 开关选择调用方式
- `AiConfig.kt` — 新增 toolEnabled 布尔配置（默认开启）
- `AiConfigDialog.kt` — 读取/保存 toolEnabled 开关
- `dialog_ai_config.xml` — 新增 ThemeSwitch 控件
- `AiChatActivity.kt` — 过滤 tool/system role 消息不显示
- `strings.xml` — 新增 ai_tool_enabled 字符串

### 8 个只读工具
get_bookshelf, search_bookshelf, get_book_sources, get_rss_sources, get_reading_stats, get_book_chapters, get_book_groups, get_source_groups

### 关键设计
- 数据精简：每个工具只返回必要字段，不返回 intro/ruleContent 等大字段
- 数量限制：书架/书源最多100条，章节目录最多200条
- 两个 API 方法：requestOpenAiMessage（支持 tool_calls）和 requestOpenAi（纯文本，用于总结记忆）

---

## Phase 2 — 写操作工具

### 修改文件
- `ToolRouter.kt` — 返回类型改为密封类 ToolExecuteResult（Data / NeedConfirmation）；新增6个写操作
- `AiToolDef.kt` — 新增6个写操作工具定义
- `AiChatViewModel.kt` — 新增 ConfirmationRequest + confirmationLiveData + confirmAction；requestWithTools 处理 NeedConfirmation；buildSystemPrompt 追加工具使用指南
- `AiChatActivity.kt` — 观察 confirmationLiveData，用 alert DSL 弹确认框
- `strings.xml` — 新增 ai_confirm_title

### 6 个写操作工具
update_book_group, enable_book_source, enable_rss_source, delete_book_source, update_book_source_group, delete_rss_source

### 确认机制架构
```
ViewModel (IO线程)                     Activity (Main线程)
    |-- tool 是写操作 -->
    |   创建 CompletableDeferred<Boolean>
    |   post 到 confirmationLiveData -----> 显示 alert 弹窗
    |   deferred.await() (挂起)
    |                                  |-- yesButton -> deferred.complete(true)
    |                                  |-- noButton  -> deferred.complete(false)
    |   <-- 恢复执行 --
```

---

## 独立 AI 助手入口

### 修改文件
- `pref_main.xml` — 新增"AI助手"偏好项
- `MyFragment.kt` — 新增点击处理，启动 AiChatActivity
- `AiChatActivity.kt` — 支持独立模式：isStandalone 判断、隐藏章节选择、标题改为"AI 助手"
- `AiChatViewModel.kt` — buildSystemPrompt 在 start/end=0 时跳过章节内容注入
- `activity_ai_chat.xml` — 章节选择区域新增 ID（layout_chapter_range）便于隐藏
- `strings.xml` — 新增 ai_assistant、ai_assistant_desc

### 独立模式逻辑
- `isStandalone = ReadBook.book == null`
- 隐藏章节选择区域和字数统计
- 归纳记忆功能禁用（提示在阅读界面使用）
- 系统提示词不注入章节内容

---

## Bug 修复

**问题**：写操作工具执行时，AI 报告成功但实际未生效。

**原因**：action lambda 中的 `book.save()` 等 Room DAO 调用是非挂起函数，lambda 被执行时不在 IO 线程，Room 静默失败。

**修复**：所有6个写操作的 action lambda 内部改用 `withContext(Dispatchers.IO)` 包裹 DAO 调用。

---

## 复用的已有代码

| 代码 | 用途 |
|------|------|
| `appDb.bookDao.all / getBooksByGroup` | 书架查询 |
| `appDb.bookSourceDao.allPart / enable / getBookSource` | 书源操作 |
| `appDb.rssSourceDao.all / update` | 订阅源操作 |
| `appDb.bookGroupDao.getByName / all` | 分组查询 |
| `appDb.readRecordDao.allTime / allShow` | 阅读统计 |
| `SourceHelp.deleteBookSource / deleteRssSource` | 删除源 |
| `Book.save()` | 保存书籍修改 |
| `Context.alert { yesButton / noButton }` | 确认弹窗 DSL |
| `GSON.toJson / fromJsonObject` | JSON 序列化 |

---

## 当前支持的14个工具

**只读（8个）**：get_bookshelf, search_bookshelf, get_book_sources, get_rss_sources, get_reading_stats, get_book_chapters, get_book_groups, get_source_groups

**写操作（6个）**：update_book_group, enable_book_source, enable_rss_source, delete_book_source, update_book_source_group, delete_rss_source

---

## 潜在优化方向

- 流式响应（streaming）支持
- 多 AI provider 抽象层
- token 计数和上下文裁剪
- Phase 3 工具：联网搜索、批量整理、导出数据
- 工具调用历史记录/日志
