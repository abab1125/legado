# Legado API 崩溃修复交接文档

> 日期：2026-07-07  
> 修复人：叶赫赫  
> 项目路径：`/Users/ma/开发/workSpace/legado`

---

## 一、问题描述

### 1.1 核心错误
```
请求失败: null cannot be cast to non-null type kotlin.collections.Map<kotlin.String, kotlin.Any>
```

### 1.2 触发场景
- 通过 ContentProvider API 调用 Legado 导入书源时崩溃
- AI 聊天/模型测试/章纲生成时，模型返回非 JSON 格式导致崩溃
- 上传文件时 JSON 解析失败崩溃

### 1.3 根因分析
Kotlin 空安全机制下，`Map<String, Any>` 的值类型不可空。当 JSON 中存在 null 值时，Gson 反序列化返回的 Map 里某些值为 null，强转为 `Map<String, Any>` 会触发 `TypeCastException`。

---

## 二、修复清单

| # | 文件 | 行号 | 问题 | 修复方式 |
|---|------|------|------|----------|
| 1 | `ReaderProvider.kt` | 78-101 | `insert()` 中 `values?.get(postBodyKey) as Map<String, Any>` — null 强转崩溃 | 重写为安全提取 JSON 字符串（支持 String/Map/null），返回错误信息而非崩溃 |
| 2 | `AiChatViewModel.kt` | 638 | AI 聊天响应解析 `getOrThrow()` — 非 JSON 响应直接崩溃 | 改为 `Map<String, Any?>` + `getOrNull()` + 友好错误提示 |
| 3 | `AiChatViewModel.kt` | 830-864 | `testModel()` 模型测试 — `ChatCompletion` 数据类解析失败后兜底逻辑复杂 | 直接用 `Map<String, Any?>` 安全解析，去掉 `ChatCompletion` 中间层 |
| 4 | `ChapterSummaryDialog.kt` | 127 | 章纲 AI 生成 `getOrThrow()` — 同上崩溃 | 同 #2 修复方式 |
| 5 | `AnalyzeUrl.kt` | 685 | 上传文件 `getOrNull()!!` — 双重空安全 | 改为 `getOrNull() ?: throw` 明确异常信息 |

### 额外修复
- `ReaderProvider.kt` 第 69 行：RSS 源删除错调了 `BookSourceController.deleteSources()`，已改为 `RssSourceController.deleteSources()`

---

## 三、修复详情

### 3.1 ReaderProvider.kt — ContentProvider API 安全化

**修复前**：
```kotlin
override fun insert(uri: Uri, values: ContentValues?): Uri? {
    if (sMatcher.match(uri) < 0) return null
    runBlocking {
        when (RequestCode.entries[sMatcher.match(uri)]) {
            RequestCode.SaveBookSource -> values?.let {
                BookSourceController.saveSource(values.getAsString(postBodyKey))
            }
            // ... 其他分支
            else -> null
        }?.let {
            SimpleCursor(it)
        }
    }
}
```

**修复后**：
```kotlin
override fun insert(uri: Uri, values: ContentValues?): Uri? {
    if (sMatcher.match(uri) < 0) return null
    runBlocking {
        // 安全提取 JSON 字符串，避免 null as Map 崩溃
        val postData = values?.let { cv ->
            val jsonRaw = cv.get(postBodyKey)
            when (jsonRaw) {
                is String -> jsonRaw
                is Map<*, *> -> Gson().toJson(jsonRaw)
                null -> null
                else -> Gson().toJson(jsonRaw)
            }
        }
        if (postData == null) {
            return@runBlocking SimpleCursor(ReturnData().setErrorMsg("数据不能为空"))
        }
        return@runBlocking when (RequestCode.entries[sMatcher.match(uri)]) {
            RequestCode.SaveBookSource -> BookSourceController.saveSource(postData)
            RequestCode.SaveBookSources -> BookSourceController.saveSources(postData)
            RequestCode.SaveRssSource -> RssSourceController.saveSource(postData)
            RequestCode.SaveRssSources -> RssSourceController.saveSources(postData)
            RequestCode.SaveBook -> BookController.saveBook(postData)
            RequestCode.SaveBookProgress -> BookController.saveBookProgress(postData)
            else -> ReturnData().setErrorMsg("未知的请求类型")
        }.let {
            SimpleCursor(it)
        }
    }
}
```

### 3.2 AI 响应解析 — 统一安全模式

**修复前**：
```kotlin
val jsonObject = GSON.fromJsonObject<Map<String, Any>>(responseString).getOrThrow()
```

**修复后**：
```kotlin
val jsonObject = GSON.fromJsonObject<Map<String, Any?>>(responseString).getOrNull()
    ?: throw Exception("模型返回了非 JSON 格式的内容")
```

### 3.3 testModel — 简化解析逻辑

**修复前**：
```kotlin
val chatObj = GSON.fromJsonObject<ChatCompletion>(responseString).getOrNull()
val content = if (chatObj != null) {
    chatObj.choices.firstOrNull()?.message?.content
} else {
    // 兜底：按 Map 解析
    GSON.fromJsonObject<Map<String, Any?>>(responseString).getOrNull()
        ?.let { raw -> ... }
}
content ?: "(无回复)"
```

**修复后**：
```kotlin
val raw = GSON.fromJsonObject<Map<String, Any?>>(responseString).getOrNull()
    ?: throw Exception("模型返回了非 JSON 格式的内容")

val choices = raw["choices"] as? List<*>
val first = choices?.firstOrNull() as? Map<*, *>
val message = first?.get("message") as? Map<*, *>
val content = message?.get("content") as? String
content ?: "(无回复)"
```

---

## 四、测试建议

### 4.1 ContentProvider API 测试
```kotlin
// 测试正常 JSON
val cv = ContentValues().apply { put("json", """{"bookSourceUrl":"http://test.com","bookSourceName":"test"}""") }
contentResolver.insert(Uri.parse("content://io.legado.app.release.readerProvider/bookSource/insert"), cv)

// 测试 null JSON
val cvNull = ContentValues().apply { putNull("json") }
contentResolver.insert(Uri.parse("content://io.legado.app.release.readerProvider/bookSource/insert"), cvNull)
// 预期：返回错误信息，不崩溃
```

### 4.2 AI 模型测试
- 测试正常 OpenAI 兼容 API
- 测试非标准 API（如通义千问、Azure OpenAI）
- 测试模型返回 HTML 错误页面时的容错

---

## 五、注意事项

1. **Gson 类型安全**：Kotlin 中使用 `Map<String, Any>` 时，JSON 中的 null 值会导致强转失败。应使用 `Map<String, Any?>` 或具体数据类。

2. **ContentProvider 跨进程**：`ContentValues.get()` 返回 `Object?`，需要安全类型判断。

3. **AI API 兼容性**：不同厂商的 API 响应格式可能有差异，兜底解析逻辑应使用安全类型转换。

---

## 六、相关文件索引

| 文件 | 路径 |
|------|------|
| ReaderProvider | `/Users/ma/开发/workSpace/legado/app/src/main/java/io/legado/app/api/ReaderProvider.kt` |
| AiChatViewModel | `/Users/ma/开发/workSpace/legado/app/src/main/java/io/legado/app/ui/book/read/ai/AiChatViewModel.kt` |
| ChapterSummaryDialog | `/Users/ma/开发/workSpace/legado/app/src/main/java/io/legado/app/ui/book/toc/ChapterSummaryDialog.kt` |
| AnalyzeUrl | `/Users/ma/开发/workSpace/legado/app/src/main/java/io/legado/app/model/analyzeRule/AnalyzeUrl.kt` |
| BookSourceController | `/Users/ma/开发/workSpace/legado/app/src/main/java/io/legado/app/api/controller/BookSourceController.kt` |

---

## 七、后续优化建议

1. **统一 AI 响应解析**：抽取公共方法 `parseChatResponse(responseString: String): String?`，避免重复代码。

2. **ContentProvider 错误处理**：考虑返回 `SimpleCursor(ReturnData().setErrorMsg(...))` 而非 null，让调用方能获取具体错误信息。

3. **模型测试增强**：`testModel` 可增加超时设置、重试机制、错误详情展示。
