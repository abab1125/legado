<div align="center">
<img width="125" height="125" src="app/src/main/res/drawable/iconnew.png" alt="legado"/>
<br>
阅读 - 自用增强分支
<br>
<a href="https://github.com/Jingshiro/legado" target="_blank">项目地址</a>
</div>

---

##  重要声明

**这是一个开发者自己用的阅读器。** 不是产品，不是服务，不承诺任何事，不对任何人的任何体验负责。

**本应用不提供任何书籍资源。** 它只是一个阅读器，就像一把菜刀不会自己做饭一样。你拿它做什么是你的事，后果自负。强烈建议阅读正版内容。

本项目基于 [Luoyacheng/legado](https://github.com/luoyacheng/legado)（阅读 Sigma）二次开发，继承自 [gedoor/legado](https://github.com/gedoor/legado)。没有前辈们的肩膀，我连站的地方都没有。

---

## 这是什么

一个**高自由度阅读器**的魔改自用分支。

### 已经实现的妄想

- **📊 阅读记录** — 记录你每天的阅读数据。支持统计和导出，接入了这个[可视化项目](https://github.com/Jingshiro/LegadoRecord)可以把数据画成图表，让你直观地感受到自己有多能看。
- **🤖 AI 助手** — 接了大模型，能聊天、能调工具、能查书架甚至能帮你做笔记。不想翻书的时候可以直接问它。
- **💡 想法批注** — 长按文字写下想法，生成分享卡片。支持笔记想法一键导出到 Obsidian。读完一本书，你的笔记也整理好了。
- **🧾 阅读书票** — 在书籍首页和尾部显示一张小票，记录评分和阅读时长。没什么用，但可以给你一点仪式感。
- **☁️ WebDAV 增强** — 云端备份支持直接在本地删除、重命名，不用再去网页端折腾。
- **📚 读完/刷书标签** — 给书打上「读完」「N刷」的标记，书架上一眼就能看到。也是个没有什么实际用途纯为了仪式感的功能。
- **🎨 主题导出** — 一键导出全部主题设置，方便分享或者迁移。调了三天的主题终于不会丢了。
- **🌐 Web API 扩展** — 新增了一些方便你的Agent调用的接口，这下可以让你的Openclaw/hermes agent/claude code管理你的书架了。
- **✨ UI 优化** — 全面（真的吗？）采用 Material Design 3。好不好看见仁见智，我只能照顾我自己的审美。

---

## 开源许可

[GPL-3.0](https://www.gnu.org/licenses/gpl-3.0.html)

简单说：你可以随便用、改、分发，但改完也得开源，而且必须保留原版权声明。别想着闭源卖钱，法律不允许，良心也不允许（如果你有的话）。

---

## 感谢

这些库撑起了整个项目，向它们的作者致敬：

> jsoup · JsoupXpath · json-path · rhino-android · okhttp · glide · nanohttpd · bga-qrcode-zxing · colorpicker · commons-text · markwon · hanlp · epublib-core · LyricViewX · rosemoe:editor

以及所有被我抄过代码的开源项目。你们是真正的英雄。
