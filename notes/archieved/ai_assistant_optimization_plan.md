# AI助手优化与修复计划

## 一、 Bug 修复：独立模式判断逻辑

### 问题描述
当用户在阅读一本书时打开过AI助手，书籍上下文（`ReadBook.book`）会被缓存。如果在未清除缓存的情况下回到“我的”页面再次点击“AI助手”，系统会因为 `ReadBook.book != null` 而错误地继承书籍上下文，导致独立模式判断失败。

### 修复方案
修改判断标准，增加 `Intent` 传参显式声明独立模式：
1. **`MyFragment.kt`**：修改“我的”页面的入口点击事件，通过 `putExtra("isStandalone", true)` 显式传入独立模式标记。
2. **`AiChatActivity.kt`**：更新 `isStandalone` 变量的判断逻辑，优先读取 `Intent` 中的 `isStandalone` 标记：
   ```kotlin
   private val isStandalone: Boolean get() = intent.getBooleanExtra("isStandalone", false) || ReadBook.book == null
   ```

## 二、 Feature 功能调整：记忆保存与会话恢复

### 需求分析
当前的记忆功能是：点击保存后调用 AI 模型对对话进行总结，保存总结内容，并且在后续聊天中作为固定 Prompt 上下文插入。
新需求要求：点击保存后不再调用 AI 总结，而是直接将当前的 `messages` 会话完整保存。在“记忆列表”中点击某条记录时，关闭列表弹窗，直接在聊天界面中恢复此前的对话。同时取消将所有历史记忆无脑拼接给 AI 的逻辑。

### 改造步骤

1. **修改数据结构 (`AiConfig.kt`)**
   - 升级 `AiMemoryItem` 数据类，新增 `messagesJson: String?` 字段，用于存放序列化后的对话。
   - `content` 字段的作用转变为“会话预览”（可取第一条 `user` 提问的前 20 个字作为展示）。
   - 废弃 `AiConfig.memory` 将所有历史会话无脑拼接为长字符串的做法，直接返回 `""` 避免污染现有/未来的 prompt 上下文。

2. **改造保存会话逻辑 (`AiChatViewModel.kt`)**
   - 移除 `buildSystemPrompt` 中对 `AiConfig.memory` 的注入。
   - 将原 `summarizeAndMemory` 改写为直接序列化并保存 `_messages` 的纯本地逻辑。
   - 保存时提取第一条用户发言，用作列表预览内容。
   - 增加一个 `restoreSession(messagesJson: String, chapterRange: String)` 的方法，当用户从记忆列表点击时调用，反序列化 json 恢复 `_messages`，并重新设置 UI 状态。

3. **修改界面与文案 (`AiChatActivity.kt` / `ai_chat_menu.xml`)**
   - 将菜单 `@id/menu_ai_summarize` 的标题“归纳记忆”修改为“保存会话”。
   - 移除独立模式下不允许保存会话的限制，当 `isStandalone` 为 true 时，可以保存为“独立模式会话”。
   - 菜单点击事件改为调用新的保存逻辑。

4. **记忆列表增加“点击恢复”交互 (`AiMemoryAdapter.kt` / `AiMemoryDialog.kt` / `AiConfigDialog.kt`)**
   - `AiMemoryAdapter` 中增加条目的点击事件（例如点击整个 `ConstraintLayout` 或 `LinearLayout` 容器）。
   - `AiMemoryDialog` 暴露点击事件接口。
   - 当触发点击事件时，判断 `activity as? AiChatActivity`，调用其 `viewModel.restoreSession(item.messagesJson)` 方法。
   - 恢复会话后，按顺序关闭 `AiMemoryDialog` 和 `AiConfigDialog`，让用户重新回到聊天界面继续对话。
   - `AiConfigDialog.kt` 中的 `tvMemoryLength` 文本逻辑从“字符长度”改为显示“记忆条数”（例如 `${AiConfig.memoryList.size} 条`）。
