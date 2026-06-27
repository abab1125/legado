# Legado - 互动写小说增强版

> 基于 Jingshiro/legado 改造，专注**互动写小说**场景的阅读器增强分支。

---

## 核心特性

### ✍️ 写作台（Writing Desk）
- **章纲编辑**：目录页每行右侧📝按钮，点开可编辑/自动生成章节概要
- **提示词管理**：全局化 AI 提示词，支持多模板切换
- **知识点管理**：写作知识库，可全局共享
- **章节正文 AI 工具**：`update_chapter_content`、`insert_after_chapter` 等 Function Calling 工具，AI 可直接读写正文

### 🤖 AI 助手增强
- **@引用**：对话中 @章节名 可直接引用正文上下文
- **阅读器互动按钮**：阅读页一键唤起 AI 操作
- **提示词选择器**：AI 配置页双 Tab 设计，快速切换写作/通用模式

### 📖 完整阅读功能（继承自 Legado）
- 自定义书源、TTS 朗读、WebDAV 备份
- 多格式支持（txt/epub/pdf/漫画）
- 所有原生阅读器功能保留

---

## 构建

```bash
# Fork 仓库 → 改代码 → push
# GitHub Actions 自动编译 APK
# 无需本地 Android SDK
```

CI 触发机制：`push` 到 `main` 分支自动构建并发布 Release。

---

## 开源许可

[GNU General Public License v3.0](https://www.gnu.org/licenses/gpl-3.0.html)

基于 [gedoor/legado](https://github.com/gedoor/legado) → [Luoyacheng/legado](https://github.com/luoyacheng/legado) → [Jingshiro/legado](https://github.com/Jingshiro/legado) 继承而来。

---

## 致谢

> org.jsoup:jsoup · com.squareup.okhttp3:okhttp · com.github.bumptech.glide:glide · org.nanohttpd:nanohttpd · io.github.rosemoe:editor · 以及所有开源依赖
