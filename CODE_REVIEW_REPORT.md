# Legado 项目代码审查报告

> **审查日期**: 2026-06-01
> **项目分支**: main
> **最新提交**: 28bb37c87 fix: 修复正文阅读中更多按钮弹窗的异常
> **许可证**: GPL-3.0

---

## 一、项目概述

Legado 是一个基于 Kotlin 开发的 Android 阅读器应用，采用 MVVM 架构，支持电子书、RSS、音视频播放。本分支（Jingshiro/legado）在原版基础上增加了 AI 助手、读书笔记、WebDAV 增强等自用功能。

### 技术栈

| 类别 | 技术 |
|------|------|
| 语言 | Kotlin 2.3.10, Java 17 |
| 数据库 | Room 2.7.1 (23 实体, 23 DAO, 版本 95) |
| 网络 | OkHttp 5.3.2, Cronet 128.0.6613.40 |
| UI | Material 1.13.0, ViewBinding |
| 媒体 | ExoPlayer/Media3 1.8.0, GSYVideoPlayer |
| 脚本 | Mozilla Rhino 1.8.1 |
| 加密 | Hutool Crypto 5.8.22 |
| Web 服务 | NanoHTTPD 2.3.1 |
| 模块数 | 3 (`:app`, `:modules:book`, `:modules:rhino`) |
| 源文件数 | ~891 个 Kotlin/Java 文件 |

---

## 二、安全性审查

### 🔴 严重 (CRITICAL)

#### 2.1 TLS 证书校验完全禁用

**文件**: [SSLHelper.kt](app/src/main/java/io/legado/app/help/http/SSLHelper.kt)

`SSLHelper` 定义了一个"万能信任管理器"，`checkClientTrusted()` 和 `checkServerTrusted()` 为空实现，**接受任何证书**，包括自签名、过期、伪造的证书。同时定义了 `unsafeHostnameVerifier` 直接返回 `true`。

**影响范围**: 该不安全 SSL 配置被以下组件全部使用：
- [HttpHelper.kt](app/src/main/java/io/legado/app/help/http/HttpHelper.kt) -- 主 OkHttp 客户端（全应用网络请求）
- [CronetHelper.kt](app/src/main/java/io/legado/app/lib/cronet/CronetHelper.kt) -- Cronet 引擎通过反射禁用证书校验
- [JsExtensions.kt](app/src/main/java/io/legado/app/help/JsExtensions.kt) -- Jsoup HTTP 调用（`get()`/`head()`/`post()`）

**风险**: 同一网络下的攻击者可发起 **中间人攻击 (MITM)**，截获并篡改所有流量，包括 WebDAV 凭据、AI API 密钥、Obsidian Token 等敏感数据。

#### 2.2 所有 WebView 静默接受无效 SSL 证书

**涉及文件** (5 处):
| 文件 | 行号 |
|------|------|
| [WebViewActivity.kt](app/src/main/java/io/legado/app/ui/browser/WebViewActivity.kt) | 508 |
| [BackstageWebView.kt](app/src/main/java/io/legado/app/help/http/BackstageWebView.kt) | 227, 368 |
| [WebViewLoginFragment.kt](app/src/main/java/io/legado/app/ui/login/WebViewLoginFragment.kt) | 137 |
| [ReadRssActivity.kt](app/src/main/java/io/legado/app/ui/rss/read/ReadRssActivity.kt) | 791 |
| [BottomWebViewDialog.kt](app/src/main/java/io/legado/app/ui/widget/dialog/BottomWebViewDialog.kt) | 809 |

所有 WebViewClient 的 `onReceivedSslError` 回调中直接调用 `handler?.proceed()`，**未向用户展示任何警告**即接受非法证书。

#### 2.3 Cronet 证书校验通过反射禁用

**文件**: [CronetHelper.kt](app/src/main/java/io/legado/app/lib/cronet/CronetHelper.kt:128)

使用反射将 `X509Util.sDefaultTrustManager` 替换为 unsafeTrustManagerExtensions，从根本上绕过了 Cronet 的证书链验证。

---

### 🟠 高危 (HIGH)

#### 2.4 导出的 ContentProvider 无权限保护

**文件**: [AndroidManifest.xml:538-543](app/src/main/AndroidManifest.xml)

`ReaderProvider` 设置了 `android:exported="true"` 且**未声明任何 `android:permission`**。设备上的任何应用都可以通过该 Provider 查询、插入、删除书源、RSS 源和书籍数据。

#### 2.5 敏感凭据明文存储于 SharedPreferences

| 文件 | 存储内容 |
|------|---------|
| [LocalConfig.kt](app/src/main/java/io/legado/app/help/config/LocalConfig.kt) | 应用锁密码 |
| [AiConfig.kt](app/src/main/java/io/legado/app/help/config/AiConfig.kt) | AI API Key |
| [AppConfig.kt](app/src/main/java/io/legado/app/help/config/AppConfig.kt) | Obsidian API Key |
| [AppWebDav.kt](app/src/main/java/io/legado/app/help/AppWebDav.kt) | WebDAV 账号密码 |

Android 的 SharedPreferences 以 XML 明文存储，root 设备或备份攻击可直接读取。

#### 2.6 android_id 用作加密密钥

**文件**: [BaseSource.kt](app/src/main/java/io/legado/app/data/entities/BaseSource.kt:172)

`AppConst.androidId`（来自 `Settings.Secure.ANDROID_ID`）被用作加密密钥的种子。该值在 Android 8.0 以下可被同设备其他应用读取，且可预测。

---

### 🟡 中等 (MEDIUM)

#### 2.7 网络安全配置允许明文流量

**文件**: [network_security_config.xml](app/src/main/res/xml/network_security_config.xml)

```xml
<base-config cleartextTrafficPermitted="true">
```

全局允许 HTTP 明文流量，加剧了 MITM 攻击面。

#### 2.8 导出的 Deep Link Activity 无权限保护

**文件**: [AndroidManifest.xml:375-388](app/src/main/AndroidManifest.xml)

`OnLineImportActivity` 处理 `legado://` 和 `yuedu://` 协议，`exported=true` 无权限限制。恶意应用可构造任意 URI 触发书源导入等操作。

#### 2.9 过度宽泛的权限声明

| 权限 | 风险 |
|------|------|
| `MANAGE_EXTERNAL_STORAGE` | 完整存储访问权限，已被 `tools:ignore="ScopedStorage"` 抑制警告 |
| `READ_PHONE_STATE` | 设备状态信息，已逐步废弃 |
| `SYSTEM_ALERT_WINDOW` | 悬浮窗能力，可用于 UI 欺骗攻击 |
| `REQUEST_INSTALL_PACKAGES` | 静默安装 APK 能力 |

#### 2.10 FileProvider 暴露整个外部存储根目录

**文件**: [file_paths.xml](app/src/main/res/xml/file_paths.xml)

```xml
<external-path name="external_storage_root" path="." />
```

`path="."` 授予了对整个外部存储的访问权，应缩小范围。

#### 2.11 google-services.json 提交至仓库

**文件**: [app/google-services.json](app/google-services.json)

包含 Firebase API Key、项目 ID、客户端 ID 等信息。虽然 Firebase Key 本身受安全规则保护，但暴露了项目结构和包名信息。

---

### 🔵 低风险 (LOW)

| 问题 | 位置 |
|------|------|
| `.mcp.json` 含明文 API Key（已 gitignore） | [.mcp.json](.mcp.json) |
| 测试文件硬编码 `accessKeySecret = '2222'` | [AndroidJsTest.kt](app/src/androidTest/java/io/legado/app/AndroidJsTest.kt) |
| 默认 WebDAV URL 硬编码为坚果云 | [AppWebDav.kt:41](app/src/main/java/io/legado/app/help/AppWebDav.kt) |
| 默认 Obsidian URL 使用 HTTP | [AppConfig.kt:839](app/src/main/java/io/legado/app/help/config/AppConfig.kt) |
| `allowBackup="true"` 允许 ADB 备份提取数据 | [AndroidManifest.xml:27](app/src/main/AndroidManifest.xml) |
| UMD Reader 大量 `System.out.println` 输出元数据 | [UmdReader.java](app/modules/book/src/main/java/me/ag2s/umdlib/umd/UmdReader.java) |
| ProGuard 移除所有 Log 调用（发布版无法调试） | [proguard-rules.pro](app/proguard-rules.pro) |

---

### ✅ 通过项

- 签名配置（`keystore.properties`、`.jks`）已正确 gitignore
- `gradle.properties` 无密钥/密码泄露
- `local.properties` 仅含 SDK 路径，无敏感信息
- 未发现 `MODE_WORLD_READABLE` / `MODE_WORLD_WRITEABLE` 用法
- 未发现 SQL 注入风险（`execSQL` 仅用于 VACUUM 等固定语句）

---

## 三、功能性审查

### 🔴 严重 Bug

#### 3.1 生产代码中存在未实现的 TODO

**文件**: [CronetCoroutineInterceptor.kt:85](app/src/main/java/io/legado/app/lib/cronet/CronetCoroutineInterceptor.kt)

```kotlin
override fun waitForDone(urlRequest: UrlRequest): Response {
    TODO("Not yet implemented")  // ← 运行时会抛出 NotImplementedError
}
```

如果执行路径到达此方法，应用将**直接崩溃**。当前因协程机制可能未触发，但属于潜在崩溃点。

#### 3.2 数据库允许主线程查询

**文件**: [AppDatabase.kt:68](app/src/main/java/io/legado/app/data/AppDatabase.kt)

```kotlin
.allowMainThreadQueries()
```

允许数据库查询在主线程执行，当数据库锁定或查询耗时较长时，将导致 **ANR (应用无响应)** 弹窗。对于一个数据密集型阅读应用，这是显著的性能隐患。

---

### 🟠 高风险问题

#### 3.3 ReadBook 单例线程安全问题

**文件**: [ReadBook.kt](app/src/main/java/io/legado/app/model/ReadBook.kt) (1,074 行)

`ReadBook` 是一个全局单例，持有大量可变状态（`book`、`chapterSize`、`durChapterIndex`、`durChapterPos`、`curTextChapter`、`bookSource` 等）。虽然部分操作使用了 `synchronized`，但许多公共 `var` 字段在不同协程/线程间无同步保护，存在竞态条件风险。

#### 3.4 104 处强制解包 (`!!`)

分布在 59 个文件中，高密度区域：
| 文件 | 数量 |
|------|------|
| TextFile.kt | 13 |
| FileDocExtensions.kt | 9 |
| ReadBook.kt | 4 |
| UriExtensions.kt | 4 |

每个 `!!` 都是一个潜在的 NPE 崩溃点。

#### 3.5 17+ 处 `runBlocking` 使用

高风险位置：
| 文件 | 风险 |
|------|------|
| CronetCoroutineInterceptor.kt:48 | 在 OkHttp 拦截器中使用，可能导致线程池耗尽 |
| HttpServer.kt:55 | 在 Web 服务器请求处理中使用 |
| BookController.kt:112,140,186,202,211 | API 控制器中多次阻塞调用 |
| AnalyzeUrl.kt:545,629,658 | 桥接阻塞和协程代码 |

---

### 🟡 中等问题

#### 3.6 25+ 处空 catch 块（异常被静默吞没）

| 文件 | 影响 |
|------|------|
| ExoPlayerHelper.kt:82 | 头部解析失败无日志 |
| TintHelper.kt:483 | 着色应用错误无日志 |
| ViewExtensions.kt:125,526 | 视图操作错误无日志 |
| SystemUtils.kt:29 | 吞没所有 Throwable |
| BookshelfViewModel.kt:70,84 | 书架加载错误无日志 |
| CrashHandler.kt:154 | 崩溃处理器自身出错无日志 |

**影响**: 异常被吞没导致 Bug 难以定位和调试。

#### 3.7 28 处 `e.printStackTrace()` 调用

生产环境的 `printStackTrace()` 输出到 stderr，在 Android Release 版本中通常被丢弃，**无法有效记录错误**。应替换为 `AppLog.put()` 等结构化日志。

#### 3.8 DanmakuAdapter 中的原始线程与硬编码 URL

**文件**: [DanmakuAdapter.kt:27-54](app/src/main/java/io/legado/app/help/gsyVideo/DanmakuAdapter.kt)

```kotlin
Thread {
    val url = URL("http://www.bilibili.com/favicon.ico")  // 硬编码 HTTP URL
    // 无线程池管理、无连接超时、无错误处理
}.start()
```

同时 `releaseResource()` 方法为空（TODO 标注），存在**内存泄漏风险**。

---

### 🟡 代码结构问题

#### 3.9 God Class（上帝类）— 超过 800 行的文件

共 **26 个文件**超过 800 行，Top 5：

| 文件 | 行数 | 问题 |
|------|------|------|
| ReadBookActivity.kt | **2,269** | 实现 12+ 个回调接口，极度臃肿 |
| TextChapterLayout.kt | 1,421 | 章节排版引擎过于庞大 |
| PhotoView.kt | 1,259 | 自定义图片 View |
| ObsoleteUrlFactory.kt | 1,201 | URL 兼容层 |
| JsExtensions.kt | 1,199 | JS 扩展函数集（全部 `@Suppress("unused")`） |

`ReadBookActivity.kt` 是最严重的，同时实现了 `View.OnTouchListener`、`ReadView.CallBack`、`TextActionMenu.CallBack`、`ContentTextView.CallBack`、`PopupMenu.OnMenuItemClickListener`、`ReadMenu.CallBack`、`SearchMenu.CallBack`、`ReadAloudDialog.CallBack`、`ReadBook.CallBack` 等 **12+ 个回调接口**。

#### 3.10 架构层面的不足

| 问题 | 现状 | 建议 |
|------|------|------|
| 无 Repository 层 | DAO 通过 `appDb.xxxDao` 直接访问 | 引入 Repository 抽象层 |
| 无依赖注入 | 使用 `object` + `by lazy` 手动管理单例 | 考虑 Hilt/Dagger/Koin |
| 全局状态单例过多 | `ReadBook`、`AudioPlay`、`ReadManga`、`VideoPlay`、`AppConfig`、`ReadBookConfig` 均为重状态单例 | 将状态管理移入 ViewModel |

---

### 🔵 代码质量

#### 3.11 注释代码块（9 处）

以下文件中存在大段注释代码，建议清理：

| 文件 | 内容 |
|------|------|
| ExoPlayerHelper.kt:140-147 | `setDefaultRequestProperties` 整个函数 |
| BookSource.kt:156-160 | `getReviewRule()` 方法 |
| BooksFragment.kt:133-144 | `AdapterDataObserver` 重写 |
| ExoVideoManager.kt:28-38 | `prepare` 函数 |
| TitleBar.kt:164-168, 272-273 | 布局调整代码 |
| CronetHelper.kt:59 | JSONObject 构造 |
| SimulationPageDelegate.kt:437, 485 | 绘图方法 |

#### 3.12 TODO/FIXME 标记

| 优先级 | 文件 | 内容 |
|--------|------|------|
| 🔴 | CronetCoroutineInterceptor.kt:85 | `TODO("Not yet implemented")` |
| 🟡 | DanmakuAdapter.kt:26 | FIXME: 线程管理需异步池+缓存 |
| 🟡 | DanmakuAdapter.kt:60 | TODO: ImageSpan 内存泄漏 |
| 🟢 | BiliDanmukuParser.kt:224,227 | TODO: 字体/动画未实现 |

#### 3.13 `@Suppress("unused")` 泛滥

**40+ 处** `@Suppress("unused")` 分布在各文件中，暗示可能存在大量未使用的代码路径。另有 6+ 处 `@Suppress("MemberVisibilityCanBePrivate")`，表明成员可见性设置过宽。

---

### 📊 近期提交分析

最近 10 次提交全部围绕 MD3 UI 改造：

```
28bb37c87 fix: 修复正文阅读中更多按钮弹窗的异常
494a492bc fix: 阅读页按钮功能修复、主题配置兼容
57e3af72b fix: 统一页面背景图/背景色显示逻辑
c0b2d222f fix: 移除adapter包装，改用scroll listener
1adfaeaa8 chore: 提交 updateLog.md
cde696c9f style: 设置页MD3卡片重设计
1e1b401dd style: 整体UI迭代（未完成）
6e86b8a47 style: 整体UI迭代（未完成）
704ab7845 style: MD3全面UI重写
faa6ce23b style: 全面MD3颜色系统迁移
```

**模式**: 提交 `1e1b401dd` 和 `6e86b8a47` 明确标注"未完成"，后续 4 次提交均为修复这些问题的 hotfix。这表明 MD3 迁移采用了**大规模提交 + 缺少充分测试**的方式，导致连锁修复。

---

## 四、问题优先级汇总

| 优先级 | 问题 | 位置 |
|--------|------|------|
| 🔴 CRITICAL | TLS 证书校验完全禁用 | SSLHelper.kt, HttpHelper.kt, CronetHelper.kt |
| 🔴 CRITICAL | 生产代码含 `TODO("Not yet implemented")` | CronetCoroutineInterceptor.kt:85 |
| 🔴 CRITICAL | 数据库允许主线程查询 (ANR 风险) | AppDatabase.kt:68 |
| 🟠 HIGH | WebView 静默接受无效证书 (5 处) | 多个 WebView 文件 |
| 🟠 HIGH | 导出的 ContentProvider 无权限保护 | AndroidManifest.xml |
| 🟠 HIGH | 敏感凭据明文存储于 SharedPreferences | LocalConfig/AiConfig/AppConfig/AppWebDav |
| 🟠 HIGH | ReadBook 单例线程安全问题 | ReadBook.kt |
| 🟠 HIGH | 104 处 `!!` 强制解包 (NPE 风险) | 59 个文件 |
| 🟠 HIGH | 17+ 处 `runBlocking` (死锁风险) | CronetCoroutineInterceptor, HttpServer 等 |
| 🟡 MEDIUM | 网络安全配置允许明文 HTTP | network_security_config.xml |
| 🟡 MEDIUM | 导出 Deep Link Activity 无权限 | AndroidManifest.xml |
| 🟡 MEDIUM | 25+ 处空 catch 块静默吞没异常 | 多处 |
| 🟡 MEDIUM | 28 处 `e.printStackTrace()` | 多处 |
| 🟡 MEDIUM | 26 个文件超过 800 行 (God Class) | ReadBookActivity 等 |
| 🟡 MEDIUM | 无 Repository 层 / 无依赖注入 | 架构层面 |
| 🟡 MEDIUM | MD3 迁移不完整，提交缺乏测试 | 近 10 次提交 |

---

## 五、改进建议

### 安全性优先修复

1. **[紧急]** 移除 `SSLHelper.kt` 中的 `unsafeTrustManager`，改用系统默认证书信任链；仅对自签名源保留可选的、用户显式授权的不安全模式
2. **[紧急]** 为 `ReaderProvider` 添加 `android:permission` 或 `android:readPermission`/`android:writePermission` 保护
3. **[高]** WebView 的 `onReceivedSslError` 中改为 `handler?.cancel()` 并向用户展示警告
4. **[高]** 将 `network_security_config.xml` 的 `cleartextTrafficPermitted` 改为 `false`
5. **[中]** 使用 `EncryptedSharedPreferences` 替代明文 SharedPreferences 存储敏感凭据
6. **[中]** 缩小 FileProvider 的 `path` 范围

### 功能性优先修复

1. **[紧急]** 实现或移除 `CronetCoroutineInterceptor.waitForDone()` 中的 `TODO`
2. **[紧急]** 移除 `.allowMainThreadQueries()`，所有数据库操作迁移到后台线程
3. **[高]** 为 `ReadBook` 的可变状态添加 `@Volatile` 或 `synchronized` 保护
4. **[高]** 逐步将 `!!` 替换为安全调用 `?.` 或 `?:` 默认值
5. **[高]** 将 `runBlocking` 替换为 `suspend` 函数或 `withContext(Dispatchers.IO)`
6. **[中]** 统一错误处理：用 `AppLog.put()` 替换空 catch 块和 `printStackTrace()`
7. **[中]** 拆分 `ReadBookActivity.kt`，将回调逻辑分离到独立类

---

*报告由 Claude Code 自动生成，建议结合实际运行测试进一步验证。*
