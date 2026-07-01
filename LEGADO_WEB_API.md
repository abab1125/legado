# Legado Web Service API Reference

> 本文档描述 legado 阅读应用的 Web 服务接口，供 AI Agent 自动化调用。

## Overview

Legado 提供 HTTP + WebSocket 两种 Web 服务：

| 服务 | 默认端口 | 说明 |
|------|----------|------|
| HTTP | 1122 | RESTful API + 静态页面 |
| WebSocket | 1123 | 实时搜索和调试 |

连接地址示例：`http://<device-ip>:1122`

## Response Format

所有 HTTP API 返回统一 JSON 格式：

```json
{
  "isSuccess": true,
  "errorMsg": "",
  "data": <any>
}
```

失败时 `isSuccess` 为 `false`，`errorMsg` 包含错误描述。

---

## Data Models

### Book

```typescript
{
  name: string              // 书名
  author: string            // 作者
  bookUrl: string           // 书籍唯一标识 URL
  tocUrl: string            // 目录页 URL
  origin: string            // 书源 URL（本地书籍为 "local"）
  originName: string        // 书源名称或本地文件名
  type: number              // BookType: 0=文本, 1=音频, 2=图片, 3=文件
  group: number             // 分组索引
  coverUrl?: string         // 封面 URL
  customCoverUrl?: string   // 自定义封面 URL
  intro?: string            // 简介
  customTag?: string        // 自定义分类
  charset?: string          // 字符集（本地书籍）
  kind?: string             // 分类信息
  wordCount?: string        // 字数
  latestChapterTitle?: string  // 最新章节标题
  latestChapterTime: number    // 最新章节更新时间
  lastCheckTime: number        // 最近检查时间
  lastCheckCount: number       // 最近发现新章节数
  totalChapterNum: number      // 目录总章节数
  durChapterTitle?: string     // 当前阅读章节标题
  durChapterIndex: number      // 当前阅读章节索引
  durChapterPos: number        // 当前阅读位置（字符索引）
  durChapterTime: number       // 最近阅读时间戳
  canUpdate: boolean           // 是否在书架刷新时更新
  order: number                // 手动排序
  originOrder: number          // 书源排序
  syncTime: number             // 同步时间
  readConfig?: ReadConfig      // 阅读设置
  variable?: string            // 变量
}
```

### BookChapter

```typescript
{
  url: string               // 章节地址
  title: string             // 章节标题
  bookUrl: string           // 书籍地址
  index: number             // 章节序号
  baseUrl: string           // 用于拼接相对 URL
  isVolume: boolean         // 是否卷名
  isVip: boolean            // 是否 VIP
  isPay: boolean            // 是否已购买
  resourceUrl?: string      // 音频真实 URL
  tag?: string              // 附加信息（如更新时间）
  start?: number            // 章节起始位置
  end?: number              // 章节终止位置
  startFragmentId?: string  // EPUB fragmentId
  endFragmentId?: string    // EPUB next fragmentId
  variable?: string         // 变量
}
```

### BookProgress

```typescript
{
  name: string
  author: string
  durChapterIndex: number
  durChapterPos: number
  durChapterTime: number
  durChapterTitle?: string
}
```

### BookSource

```typescript
{
  bookSourceUrl: string       // 书源地址（唯一标识）
  bookSourceName: string      // 书源名称
  bookSourceGroup?: string    // 分组
  bookSourceType: number      // 类型: 0=文本, 1=音频, 2=图片, 3=文件
  bookUrlPattern?: string     // 详情页 URL 正则
  customOrder: number         // 手动排序
  enabled: boolean            // 是否启用
  enabledExplore: boolean     // 启用发现
  bookSourceComment?: string  // 注释
  searchUrl?: string          // 搜索 URL
  exploreUrl?: string         // 发现 URL
  loginUrl?: string           // 登录地址
  loginUi?: string            // 登录 UI
  loginCheckJs?: string       // 登录检测 JS
  header?: string             // 请求头
  jsLib?: string              // JS 库
  concurrentRate?: string     // 并发率
  enabledCookieJar?: boolean  // 启用 CookieJar
  weight: number              // 智能排序权重
  lastUpdateTime: number      // 最后更新时间
  respondTime: number         // 响应时间
  ruleExplore?: object        // 发现规则
  ruleSearch?: object         // 搜索规则
  ruleBookInfo?: object       // 书籍信息规则
  ruleToc?: object            // 目录规则
  ruleContent?: object        // 正文规则
}
```

### RssSource

```typescript
{
  sourceUrl: string           // 订阅源地址（唯一标识）
  sourceName: string          // 名称
  sourceIcon: string          // 图标
  sourceGroup?: string        // 分组
  sourceComment?: string      // 注释
  enabled: boolean            // 是否启用
  singleUrl: boolean          // 是否单 URL 源
  articleStyle: number        // 列表样式: 0, 1, 2
  sortUrl?: string            // 分类 URL
  ruleArticles?: string       // 列表规则
  ruleNextPage?: string       // 下一页规则
  ruleTitle?: string          // 标题规则
  rulePubDate?: string        // 发布日期规则
  ruleDescription?: string    // 描述规则
  ruleImage?: string          // 图片规则
  ruleLink?: string           // 链接规则
  ruleContent?: string        // 正文规则
  customOrder: number         // 排序
  lastUpdateTime: number      // 更新时间
  loginUrl?: string           // 登录地址
  header?: string             // 请求头
  enableJs: boolean           // 启用 JS
  loadWithBaseUrl: boolean    // 加载 BaseURL
  injectJs?: string           // 注入 JS
  style?: string              // WebView 样式
}
```

### ReplaceRule

```typescript
{
  id: number                // 唯一 ID
  name: string              // 名称
  group?: string            // 分组
  pattern: string           // 匹配规则（正则）
  replacement: string       // 替换为
  scope?: string            // 作用范围（书源 URL）
  scopeTitle: boolean       // 作用于标题
  scopeContent: boolean     // 作用于正文
  excludeScope?: string     // 排除范围
  isEnabled: boolean        // 是否启用
}
```

### webReadConfig

```typescript
{
  theme: number             // 主题
  font: number              // 字体
  fontSize: number          // 字号
  readWidth: number         // 阅读宽度
  infiniteLoading: boolean  // 无限加载
  customFontName: string    // 自定义字体名
  jumpDuration: number      // 跳转动画时长
  spacing: {
    paragraph: number       // 段落间距
    line: number            // 行距
    letter: number          // 字间距
  }
}
```

---

## HTTP API Endpoints

### Bookshelf

#### GET `/getBookshelf`

获取书架所有书籍。

- **Parameters**: 无
- **Response**: `Book[]`

```bash
curl "http://<ip>:1122/getBookshelf"
```

#### POST `/saveBook`

保存或添加书籍到书架。

- **Body**: `Book` (JSON)
- **Response**: `string` (成功消息)

#### POST `/deleteBook`

从书架删除书籍。

- **Body**: `Book` (JSON, 需包含 `bookUrl`)
- **Response**: `string`

#### POST `/saveBookProgress`

保存阅读进度。

- **Body**: `BookProgress` (JSON)
- **Response**: `string`

```bash
curl -X POST "http://<ip>:1122/saveBookProgress" \
  -H "Content-Type: application/json" \
  -d '{"name":"书名","author":"作者","durChapterIndex":5,"durChapterPos":0,"durChapterTime":1234567890,"durChapterTitle":"第六章"}'
```

---

### Chapters & Content

#### GET `/getChapterList`

获取书籍章节列表。

- **Parameters**: `url` (string, 书籍地址, required)
- **Response**: `BookChapter[]`

```bash
curl "http://<ip>:1122/getChapterList?url=<bookUrl>"
```

#### GET `/refreshToc`

从源重新获取目录。

- **Parameters**: `url` (string, 书籍地址, required)
- **Response**: `BookChapter[]`

#### GET `/getBookContent`

获取指定章节正文内容。

- **Parameters**:
  - `url` (string, 书籍地址, required)
  - `index` (number, 章节序号, required)
- **Response**: `string` (正文 HTML/文本)

```bash
curl "http://<ip>:1122/getBookContent?url=<bookUrl>&index=0"
```

#### GET `/cover`

获取书籍封面图片。

- **Parameters**: `path` (string, 封面路径, required)
- **Response**: `image/png` (二进制图片)

#### GET `/image`

获取正文中的图片。

- **Parameters**:
  - `url` (string, 书籍地址, required)
  - `path` (string, 图片链接, required)
  - `width` (number, 默认 640)
- **Response**: `image/png`

---

### Book Sources

#### GET `/getBookSources`

获取所有书源。

- **Parameters**: 无
- **Response**: `BookSource[]`

#### GET `/getBookSource`

获取指定书源。

- **Parameters**: `url` (string, 书源地址, required)
- **Response**: `BookSource`

#### POST `/saveBookSource`

保存单个书源。

- **Body**: `BookSource` (JSON)
- **Response**: `string`

#### POST `/saveBookSources`

批量保存书源。

- **Body**: `BookSource[]` (JSON)
- **Response**: `BookSource[]`

#### POST `/deleteBookSources`

批量删除书源。

- **Body**: `BookSource[]` (JSON)
- **Response**: `string`

---

### RSS Sources

#### GET `/getRssSources`

获取所有订阅源。

- **Parameters**: 无
- **Response**: `RssSource[]`

#### GET `/getRssSource`

获取指定订阅源。

- **Parameters**: `url` (string, 订阅源地址, required)
- **Response**: `RssSource`

#### POST `/saveRssSource`

保存单个订阅源。

- **Body**: `RssSource` (JSON)
- **Response**: `string`

#### POST `/saveRssSources`

批量保存订阅源。

- **Body**: `RssSource[]` (JSON)
- **Response**: `RssSource[]`

#### POST `/deleteRssSources`

批量删除订阅源。

- **Body**: `RssSource[]` (JSON)
- **Response**: `string`

---

### Replace Rules

#### GET `/getReplaceRules`

获取所有替换规则。

- **Parameters**: 无
- **Response**: `ReplaceRule[]`

#### POST `/saveReplaceRule`

保存替换规则。

- **Body**: `ReplaceRule` (JSON)
- **Response**: `string`

#### POST `/deleteReplaceRule`

删除替换规则。

- **Body**: `ReplaceRule` (JSON, 需包含 `id`)
- **Response**: `string`

#### POST `/testReplaceRule`

测试替换规则效果。

- **Body**: `{ "rule": ReplaceRule, "text": "测试文本" }` (JSON)
- **Response**: `string` (替换后的文本)

---

### Book Thoughts (读书想法)

#### GET `/getBookThoughts`

获取某本书的所有想法。

- **Parameters**:
  - `bookName` (string, 书名, required)
  - `bookAuthor` (string, 作者, required)
- **Response**: `BookThought[]`

```bash
curl "http://<ip>:1122/getBookThoughts?bookName=书名&bookAuthor=作者"
```

#### GET `/getThoughtsByChapter`

获取某章节的想法。

- **Parameters**:
  - `bookName` (string, 书名, required)
  - `bookAuthor` (string, 作者, required)
  - `index` (number, 章节序号, required)
- **Response**: `BookThought[]`

```bash
curl "http://<ip>:1122/getThoughtsByChapter?bookName=书名&bookAuthor=作者&index=0"
```

#### POST `/saveBookThought`

保存想法（新建或更新）。`id` 为 0 时新建，否则更新。

- **Body**: `BookThought` (JSON)
- **Response**: `string`

#### POST `/deleteBookThought`

删除想法。

- **Body**: `BookThought` (JSON)
- **Response**: `string`

---

### Read Records (阅读记录)

#### GET `/getReadRecords`

获取所有书籍的阅读记录汇总（按书名分组，合并多设备时长）。

- **Parameters**: `searchKey` (string, optional, 按书名模糊搜索)
- **Response**: `ReadRecordShow[]`

```typescript
// ReadRecordShow
{
  bookName: string   // 书名
  readTime: number   // 总阅读时长（毫秒）
  lastRead: number   // 最近阅读时间戳
}
```

```bash
curl "http://<ip>:1122/getReadRecords"
curl "http://<ip>:1122/getReadRecords?searchKey=关键词"
```

#### GET `/getReadTime`

获取总阅读时长，或指定书籍的阅读时长。

- **Parameters**: `bookName` (string, optional, 不传则返回总时长)
- **Response**: `number` (毫秒)

```bash
curl "http://<ip>:1122/getReadTime"
curl "http://<ip>:1122/getReadTime?bookName=书名"
```

#### GET `/getDetailedReadRecords`

获取详细阅读记录，支持多维度筛选。

- **Parameters**:
  - `bookName` (string, optional) — 按书名模糊筛选
  - `startTime` (number, optional, 毫秒时间戳) — 筛选开始时间 >= startTime 的记录
  - `endTime` (number, optional, 毫秒时间戳) — 筛选结束时间 <= endTime 的记录
- **Response**: `DetailedReadRecord[]`

```typescript
// DetailedReadRecord
{
  id: number           // 唯一 ID
  bookName: string     // 书名
  startTime: number    // 开始阅读时间戳
  endTime: number      // 结束阅读时间戳
  readIteration: number // 阅读迭代次数
}
```

```bash
curl "http://<ip>:1122/getDetailedReadRecords"
curl "http://<ip>:1122/getDetailedReadRecords?bookName=书名"
curl "http://<ip>:1122/getDetailedReadRecords?startTime=1700000000000&endTime=1700100000000"
curl "http://<ip>:1122/getDetailedReadRecords?bookName=书名&startTime=1700000000000&endTime=1700100000000"
```

---

### Reading Config

#### GET `/getReadConfig`

获取 Web 阅读界面配置。

- **Parameters**: 无
- **Response**: `webReadConfig` (JSON 字符串, 需 parse)

#### POST `/saveReadConfig`

保存 Web 阅读界面配置。

- **Body**: `webReadConfig` (JSON)
- **Response**: `string`

---

### Local Book Upload

#### POST `/addLocalBook`

上传并添加本地书籍（multipart/form-data）。

- **Body**:
  - `fileName`: 文件名
  - `fileData`: 文件二进制内容
- **Response**: `string`

```bash
curl -X POST "http://<ip>:1122/addLocalBook" \
  -F "fileName=mybook.txt" \
  -F "fileData=@/path/to/mybook.txt"
```

---

## WebSocket API

WebSocket 服务运行在 HTTP 端口 + 1（默认 1123）。

连接地址：`ws://<device-ip>:1123`

### `/searchBook`

搜索在线书籍。

**Send**:
```json
{ "key": "搜索关键词" }
```

**Receive** (多次推送):
```json
[
  {
    "name": "书名",
    "author": "作者",
    "bookUrl": "...",
    "origin": "书源URL",
    "originName": "书源名称",
    "type": 0,
    "coverUrl": "...",
    "intro": "...",
    "latestChapterTitle": "...",
    "tocUrl": "..."
  }
]
```

连接在搜索完成后自动关闭。

### `/bookSourceDebug`

调试书源。

**Send**:
```json
{ "tag": "书源URL", "key": "搜索关键词" }
```

**Receive**: 多条调试日志文本，完成后连接关闭。

### `/rssSourceDebug`

调试订阅源。

**Send**:
```json
{ "tag": "订阅源URL" }
```

**Receive**: 多条调试日志文本，完成后连接关闭。

---

## Common Workflows

### 1. 获取书架并阅读

```
GET /getBookshelf
  -> 获取书籍列表，提取 bookUrl

GET /getChapterList?url=<bookUrl>
  -> 获取章节列表，提取章节 index

GET /getBookContent?url=<bookUrl>&index=<chapterIndex>
  -> 获取正文内容
```

### 2. 搜索并添加书籍

```
WebSocket /searchBook
  -> 发送 { "key": "关键词" }
  -> 接收搜索结果数组

POST /saveBook
  -> 保存选中的书籍到书架
```

### 3. 管理书源

```
GET /getBookSources
  -> 获取所有书源

POST /saveBookSource
  -> 添加/更新单个书源

POST /deleteBookSources
  -> 删除不需要的书源
```

### 4. 同步阅读进度

```
POST /saveBookProgress
  -> 发送 BookProgress 对象同步进度

GET /getBookshelf
  -> 获取当前进度
```

### 5. 上传本地书籍

```
POST /addLocalBook (multipart)
  -> 上传文件

GET /getBookshelf
  -> 确认书籍已添加
```

---

## CORS

所有 API 支持跨域请求，OPTIONS 预检返回：

```
Access-Control-Allow-Origin: *
Access-Control-Allow-Methods: POST
Access-Control-Allow-Headers: content-type
```

---

## Static Pages

| Path | Description |
|------|-------------|
| `/` | 导航首页 |
| `/vue/index.html` | Vue SPA 管理界面 |
| `/vue/index.html#/` | 书架 |
| `/vue/index.html#/chapter` | 章节阅读 |
| `/vue/index.html#/bookSource` | 书源编辑 |
| `/vue/index.html#/rssSource` | 订阅源编辑 |
| `/uploadBook/index.html` | WiFi 传书 |
| `/help/index.html` | 帮助文档 |
