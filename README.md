# Legado - 互动写小说增强版

> 基于 Jingshiro/legado 改造，专注**互动写小说**场景的阅读器增强分支。

---

##  重要声明

**这是一个开发者自己用的阅读器。** 不是产品，不是服务，不承诺任何事，不对任何人的任何体验负责。

**本应用不提供任何书籍资源。** 它只是一个阅读器，就像一把菜刀不会自己做饭一样。你拿它做什么是你的事，后果自负。强烈建议阅读正版内容。

本项目基于 [Luoyacheng/legado](https://github.com/luoyacheng/legado)（阅读 Sigma）二次开发，继承自 [gedoor/legado](https://github.com/gedoor/legado)。没有前辈们的肩膀，我连站的地方都没有。

---

## 核心特性

### ✍️ 写作台（Writing Desk）
- **章纲编辑**：目录页每行右侧📝按钮，点开可编辑/自动生成章节概要
- **提示词管理**：全局化 AI 提示词，支持多模板切换
- **知识点管理**：写作知识库，可全局共享，支持三级钻取（分类→小说名→角色卡）
- **章节正文 AI 工具**：`update_chapter_content`、`insert_chapter_text` 等 Function Calling 工具，AI 可直接读写正文
- **提取角色**：多选章节调用 LLM 批量提取小说角色卡

### 🤖 AI 助手增强
- **@引用**：对话中 @章节名/@知识点 可直接引用正文与知识库上下文
- **阅读器互动按钮**：阅读页一键唤起 AI 操作
- **提示词选择器**：AI 配置页双 Tab 设计，快速切换写作/通用模式

### 📖 完整阅读功能（继承自 Legado）
- 自定义书源、TTS 朗读、WebDAV 备份
- 多格式支持（txt/epub/pdf/漫画）
- 高亮规则、主题自定义、Obsidian 想法导出（上游特性）
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

简单说：你可以随便用、改、分发，但改完也得开源，而且必须保留原版权声明。别想着闭源卖钱，法律不允许，良心也不允许（如果你有的话）。

基于 [gedoor/legado](https://github.com/gedoor/legado) → [Luoyacheng/legado](https://github.com/luoyacheng/legado) → [Jingshiro/legado](https://github.com/Jingshiro/legado) 继承而来。

---

## 致谢

这些库撑起了整个项目，向它们的作者致敬：

> jsoup · JsoupXpath · json-path · rhino-android · okhttp · glide · nanohttpd · bga-qrcode-zxing · colorpicker · commons-text · markwon · hanlp · epublib-core · LyricViewX · rosemoe:editor

以及所有被我抄过代码的开源项目。你们是真正的英雄。
