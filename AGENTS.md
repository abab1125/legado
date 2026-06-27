# Legdo 项目文档

> 基于 Jingshiro/legado 改造的互动写小说前端
>
> 项目代号：legdo（用户指定简称）
>
> 更新时间：2026-06-27

---

## ⚡ 铁律：跨文件改动先跑 CodeGraph

涉及**重构、加字段、增删方法、改数据源、改 DAO** 等牵一发动全身的操作，必须先查影响范围：

```bash
cd /Users/ma/开发/workSpace/legado
codegraph impact "类名"   # 改它炸哪些地方
codegraph callers "类名"  # 谁在用，改了会不会断链
codegraph callees "类名"  # 它依赖了谁
```

单文件 bugfix 不用查，但跨文件的改动不做纯 grep 盲改。

---

## 一、项目背景

**基座仓库**：Jingshiro/legado（gedoor/legado 的第三方增强 fork）

- 继承链：gedoor/legado（官方，已清空）→ Luoyacheng/legado（Sigma版）→ **Jingshiro/legado**（当前基座）
- 最新构建版本：3.26.062619（2026-06-26/27 Release）
- 源码克隆位置：`/Users/ma/开发/workSpace/legado/`
- 编译产物：GitHub Releases 提供标准版和 releaseS 版（包名不同，功能一致）
- repo 大小：~36M（depth=1 克隆，最新 commit 4c6e3a2）

**目标**：改造为互动写小说 App——保留阅读功能的同时，加入章节概要（章纲）编辑、AI 写作工具等创作侧功能。

**构建策略**：fork → 改代码 → push → GitHub Actions 自动编译 APK，零本地 Android SDK 依赖。

---

## 二、项目结构（关键目录）

```
legado/
├── app/
│   ├── build.gradle           # compileSdk=36, minSdk=21, targetSdk=36, viewBinding=true
│   └── src/main/
│       ├── res/
│       │   ├── layout/                    # XML 布局
│       │   │   ├── item_chapter_list.xml  # 章节列表行（已加章纲按钮）
│       │   │   └── dialog_chapter_summary.xml  # [新增]章纲编辑对话框
│       │   └── drawable/                  # 图标（ic_edit.xml 等）
│       └── java/io/legado/app/
│           ├── base/
│           │   └── BaseDialogFragment.kt  # DialogFragment 基类
│           ├── data/
│           │   ├── entities/
│           │   │   ├── BookChapter.kt     # 章节实体（含 variable 字段存储章纲）
│           │   │   └── Book.kt
│           │   └── dao/
│           │       └── BookChapterDao.kt  # Room DAO
│           ├── help/
│           │   ├── book/BookHelp.kt       # getContent() 读取章节正文
│           │   └── config/AiConfig.kt     # AI 配置（URL/Key/Model）
│           └── ui/book/
│               ├── read/
│               │   └── ai/
│               │       ├── AiChatActivity.kt     # AI 聊天界面
│               │       ├── AiChatViewModel.kt    # LLM 调用封装（OpenAI 兼容 HTTP）
│               │       ├── ChatAdapter.kt        # 聊天列表适配器
│               │       └── tool/
│               │           ├── AiToolDef.kt      # 20+ Function Calling 工具定义
│               │           └── ToolRouter.kt     # 工具路由（含 ToolExecuteResult）
│               └── toc/
│                   ├── ChapterListFragment.kt  # 目录列表 Fragment（已改）
│                   ├── ChapterListAdapter.kt   # 目录适配器（已改）
│                   ├── TocViewModel.kt         # 目录 ViewModel
│                   └── ChapterSummaryDialog.kt # [新增]章纲编辑对话框
└── .github/workflows/
    └── build_and_release.yml           # CI 配置（release + releaseS 双 APK）
```

---

## 三、已完成改造：章节概要（章纲）功能

### 3.1 数据存储

- **位置**：`BookChapter.variable` 字段
- **类型**：Room SQLite 列，存 JSON 序列化的 `HashMap<String, String>`
- **Key**：`"summary"`
- **优势**：不新建表、不迁移数据库，外键 CASCADE 随书自动删除
- **现有 key**：`lyric`（有声书歌词）、`danmaku`（弹幕）、规则引擎变量——`summary` 无冲突
- **存取 API**：
  - 读：`chapter.variableMap["summary"]`
  - 写：`chapter.putVariable("summary", text)` + `chapter.update()`

### 3.2 交互流程

```
打开目录 → 每行右侧📝按钮
    灰色 = 无章纲    蓝色 = 已有章纲

点击📝 → 弹窗全屏编辑章纲
    可选「AI生成」 → 读本章正文 → 调 LLM → 填入文本框
    点击「保存」   → 写入 SQLite → 图标刷新
```

### 3.3 改动清单（6 文件 2 新增）

| 文件 | 操作 | 说明 |
|------|------|------|
| `res/layout/dialog_chapter_summary.xml` | **新增** | 弹窗布局 |
| `ui/book/toc/ChapterSummaryDialog.kt` | **新增** | 弹窗逻辑（读/写/LLM 调用） |
| `res/layout/item_chapter_list.xml` | 修改 | 每行加 `iv_summary` 按钮 |
| `ui/book/toc/ChapterListAdapter.kt` | 修改 | 加 `openSummary` 回调 + 图标状态 |
| `ui/book/toc/ChapterListFragment.kt` | 修改 | 接弹窗 + 关闭后刷新行 |
| `ui/book/toc/TocViewModel.kt` | 未动 | 不需要改 |

---

## 四、技术架构

### 4.1 AI 通路

- **模式**：客户端 Function Calling（legado 原生 AI 助手模式）
- **HTTP 客户端**：OkHttp（`AiChatViewModel.kt` 封装，read/call 均 120s 超时）
- **API 格式**：OpenAI 兼容 `/v1/chat/completions`
- **配置存储**：`AiConfig`（SharedPreferences 持久化 apiUrl / apiKey / model）
- **章纲生成**：复用同一配置，截取正文前 6000 字 + prompt，max_tokens=500

### 4.2 数据流

```
用户编辑章纲 → ChapterSummaryDialog
                  │
                  ▼ 点击保存
            putVariable("summary", text)
                  │
                  ▼
            chapter.update() → Room: chapters表.variable列更新
                  │
                  ▼ 关闭弹窗
            onDismissListener → adapter.notifyItemChanged()
                                    → 图标变色（有summary→蓝色，无→灰色）
```

### 4.3 后续扩展方向

1. **AI 写作工具**（已规划）：在 `read/ai/tool/AiToolDef.kt` 加 `get_story_context` / `save_chapter` 工具，打通 AI 助手与章纲/正文的读写能力
2. **章纲搜索/过滤**：利用 Room `chapters` 表的 `@Query("LIKE '%summary%'")` 按概要过滤目录
3. **章纲导出**：批量导出为写作计划/大纲文档

---

## 五、开发环境

- **本地**：macOS 26.5.1
- **编辑器**：VS Code / 任意文本编辑器
- **编译**：GitHub Actions CI（push 触发）
- **无需**：本地 Android SDK、Gradle、模拟器
- **部署流程**：
  1. fork 仓库
  2. 改代码后 commit & push
  3. GitHub Actions 自动构建
  4. 去 Releases 页下载 APK

---

## 六、关键已知事项

- **用户写作风格**：网文土味、历史军事题材（宋金岳飞相关）
- **章节正文获取**：`BookHelp.getContent(book, chapter)` 从缓存文件读取
- **dialog 基类**：`BaseDialogFragment`（有 `setOnDismissListener`、`setLayout`、`execute{}` 由 `lifecycleScope` 替代）
- **章纲图标变色**：`ivSummary.imageTintList` 通过 `ColorStateList` 设置，API 21+ 兼容
- **章纲按钮位置**：列表行右侧，`iv_checked`（缓存状态图标）左侧
- **ViewBinding**：已启用（`buildFeatures.viewBinding = true`），布局文件命名需匹配 `snake_case → CamelCase` 规则

---

## 七、Android 开发守则（Legado 专属版）

### 7.1 技术栈（已确认）

| 组件 | 版本 | 说明 |
|------|------|------|
| Kotlin | `2.3.10` | K2 编译器已启用（ksp 非 kapt）|
| AGP | `8.13.2` | |
| Room | `2.7.1` | KSP 编译 |
| OkHttp | `5.3.2` | 通信基础 |
| GSON | `2.13.2` | JSON 序列化（非 kotlinx.serialization）|
| coroutines | `1.10.2` | |
| compileSdk | `36` | minSdk=21, targetSdk=36 |
| JDK | 17 | |
| UI 方案 | XML + ViewBinding + Fragment | **无 Compose，无 Jetpack Navigation，无 Hilt** |
| DI 方案 | **无**（手动传参 + splitties + 全局单例）|

> **重要**：Legado 的代码风格与 Compose MVI / Hilt / Navigation 3 等现代 Android 推荐实践完全不同。不要在 Legado 里引入 Compose、Hilt、Navigation Component 等——现有模式稳定且优化完善，强制混合会导致上下文膨胀和风格冲突。

### 7.2 代码模式速查

#### ViewModel 层

```kotlin
// ✅ Legado 标准写法
class XxxViewModel(application: Application) : BaseViewModel(application) {
    val xxxLiveData = MutableLiveData<Type>()

    fun doSomething() {
        execute {                          // BaseViewModel 的协程封装（lifecycleScope）
            // IO 操作直接写（默认在 IO 调度器）
            val result = dao.query()
            xxxLiveData.postValue(result)  // postValue 非 setValue（跨线程安全）
        }
    }
}

// ⚠️ 不要这样写（非 Legado 习惯，增加心智负担）
// class XxxViewModel @Inject constructor(...) : ViewModel()
// private val _state = MutableStateFlow(...)  ← Legado 不用 StateFlow
```

#### 协程

```kotlin
// ✅ 单次操作
execute {
    // 自动在 Dispatchers.IO 执行
    view.post { /* 切回主线程 */ }
}

// ✅ 需要精确控制线程
lifecycleScope.launch {
    val result = withContext(Dispatchers.IO) { heavyWork() }
    // 主线程更新 UI
}

// ✅ 异步回调用 CompletableDeferred（见 AiChatViewModel）
```

#### 实体存储

```kotlin
// ✅ 用 variableMap + putVariable 扩展字段（不建新表）
chapter.putVariable("summary", text)  // 存入 chapter 的 variable JSON
chapter.update()                      // 调 Room DAO update
chapter.variableMap["summary"]        // 读取

// ⚠️ 不要：为新功能建新 Entity 或新 Room 表，除非数据量大且关联复杂
// ✅ 例外：独立于 book/chapter 的数据可新建表
```

#### JSON 解析

```kotlin
// ✅ Legado 标准
GSON.fromJsonObject<Book>(jsonString).onSuccess { book ->
    // 类型安全
}.onFailure { error ->
    // 错误处理
}

// ⚠️ 不要用 kotlinx.serialization（Legado 无此依赖）
```

#### Fragment / DialogFragment

```kotlin
// ✅
class XxxDialog : BaseDialogFragment(R.layout.dialog_xxx) {
    private val binding by viewBinding(DialogXxxBinding::bind)

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        // 在这里初始化，不要用 onCreateView
    }
}

// ✅ 传参
companion object {
    fun newInstance(data: Data): XxxDialog {
        return XxxDialog().apply {
            arguments = Bundle().apply {
                putString("data", GSON.toJson(data))
            }
        }
    }
}
```

### 7.3 反模式清单（Legado 专属）

| 反模式 | 原因 | 正确做法 |
|--------|------|----------|
| 引入 Compose 依赖 | Legado 全 XML + ViewBinding，混用增加包体积和构建时间 | 坚持 XML 布局 |
| 引入 Hilt/Dagger | Legado 无 DI 框架，全局单例模式稳定 | 用 `object` / splitties `appctx` 获取单例 |
| 引入 Navigation Component | Legado 用 FragmentManager + 自定义导航 | 保持用 `findNavFragment()` / Fragment 栈 |
| 用 `setValue()` 而非 `postValue()` | 跨线程调用会崩溃 | 用 `postValue()` |
| 直接 `Gson().fromJson()` | 每次 new Gson 对象浪费 | 用 Legado 封装的 `GSON.fromJsonObject` |
| `viewModelScope.launch` | Legado 的 `BaseViewModel` 用 `execute{}` | 用 `execute{}` 或在 `lifecycleScope` 内写 |
| 直接调用 `BookHelp.getContent` 后不判空 | getContent 返回可空 | 判空处理 |
| 创建新 Room 表存储少量键值数据 | 增加迁移复杂度 | 用 `putVariable` / SharedPreferences |

### 7.4 AI 工具系统扩展说明

**关键文件位置**（已确认）：

```
ui/book/read/ai/
├── AiChatViewModel.kt     # 聊天逻辑、LLM 调用编排
├── AiChatActivity.kt      # 聊天界面
├── ChatAdapter.kt         # 聊天列表适配器
├── tool/
│   ├── AiToolDef.kt       # 工具定义（allTools 列表）← 写作工具加在这里
│   └── ToolRouter.kt      # 工具路由（分发执行）+ ToolExecuteResult sealed class
```

**添加工具的套路**：

1. 在 `AiToolDef.allTools` 列表末尾加新的 `tool()` 定义
2. 在 `ToolRouter` 中加 `"tool_name" -> ::executeMethod` 路由
3. 编写执行函数，返回 `ToolExecuteResult`
4. push → GitHub Actions 自动编译 APK

### 7.5 资源文件命名约定

- 布局：`snake_case`（如 `dialog_chapter_summary.xml`）
- ViewBinding 生成：`SnakeCase → SnakeCaseBinding`（`dialog_chapter_summary → DialogChapterSummaryBinding`）
- drawable：`ic_xxx.xml`（矢量图）、`bg_xxx.xml`（背景）、`shape_xxx.xml`（Shape）
- colors/strings：`snake_case`
- 不要用 `@+id/` 创建重复 ID，优先用 viewBinding

### 7.7 写作台模块（Write Desk）

`ui/write/` 下的创作功能模块，独立于阅读体系。

**数据层**（新表）：

| 表 | Entity | DAO | 用途 |
|---|--------|-----|------|
| `writing_prompts` | `WritingPrompt` | `WritingPromptDao` | 写作提示词（角色/世界观/风格/大纲） |
| `knowledge_points` | `KnowledgePoint` | `KnowledgePointDao` | 创作知识点（可关联章节） |

**数据库迁移**：96→97 手动 migration（新表，不影响旧数据）。

**UI 层**：

| Activity | 功能 | 路径 |
|----------|------|------|
| `WriteDeskActivity` | 作品列表 + 新建/删除作品 | `ui/write/WriteDeskActivity.kt` |
| `WriteBookActivity` | 单作品详情 + 目录管理 + 章纲编辑 | `ui/write/WriteBookActivity.kt` |
| `ChapterEditorActivity` | 正文编辑器（EditText + BookHelp.saveText） | `ui/write/ChapterEditorActivity.kt` |
| `PromptManageDialog` | 提示词 CRUD 弹窗 | `ui/write/PromptManageDialog.kt` |
| `KnowledgeManageDialog` | 知识点 CRUD 弹窗 | `ui/write/KnowledgeManageDialog.kt` |

**作品存储**：新作品写为本地书（`type = BookType.text or BookType.local`, `origin = BookType.localTag`），bookUrl 用 `write_UUID` 前缀。

**章节存储**：`BookChapter` 复用 Legado 现有实体，章纲存 `variableMap["outline"]`（JSON 序列化到 `chapters.text` 列），正文存 `.nb` 文件（`BookHelp.saveText()`）。

**入口**："我的"页面底部快捷按钮「写作台」。

### 7.6 分支策略

```
main                    # 稳定分支，推送到 GitHub Actions 编译 APK
├── feat/chapter-summary # 已完成：章纲功能
├── feat/ai-writing      # 计划中：AI 写作工具
└── fix/*                # 修复分支
```

推送 `main` 到 GitHub 触发 CI 编译 Release APK。

---

## 八、参考链接

- 原始仓库：https://github.com/Jingshiro/legado
- 官方 legado：https://github.com/gedoor/legado（已归档清空）
- 本项目本地路径：`/Users/ma/开发/workSpace/legado/`

## 九、CodeGraph 代码索引

本目录已建立 CodeGraph 索引（`.codegraph/`），支持符号查询、调用链追踪、影响范围分析。

**用法：**
```bash
cd /Users/ma/开发/workSpace/legado
codegraph query "ClassName"         # 搜索符号
codegraph callers ClassName         # 找谁调了它
codegraph callees ClassName         # 它调了谁
codegraph impact ClassName          # 改它影响哪些代码
codegraph files                     # 看项目文件结构
```

**注意：** CodeGraph 用完即关，不要常驻后台。
