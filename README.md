# [English](English.md) [中文](README.md)

<div align="center">
<img width="125" height="125" src="https://github.com/gedoor/legado/raw/master/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" alt="legado"/>
<br>
阅读 - 自用增强分支
<br>
<a href="https://github.com/Jingshiro/legado" target="_blank">项目地址</a>
</div>

---

## 重要声明

**本项目仅为开发者个人自用的阅读器，不对任何人的任何使用体验负责。**

**本应用不提供任何书籍资源，不内置任何内容，不鼓励、不支持、不协助任何人使用本软件阅读盗版书籍。强烈建议所有使用者仅将本软件用于阅读正版授权内容。因使用本软件产生的一切法律责任和后果由使用者自行承担，开发者概不负责。**

本项目基于 [Luoyacheng/legado](https://github.com/luoyacheng/legado)（阅读 Sigma）进行二次开发，在此衷心感谢前辈们提供的代码基础和技术支持。阅读 Sigma 继承自 [gedoor/legado](https://github.com/gedoor/legado)，本项目站在巨人的肩膀上，才得以实现更多功能。


---

## 关于本项目

本项目是一个**高自由度的自定义阅读器**，核心理念是让用户完全掌控自己的阅读体验。在阅读（Legado）的基础架构上，本分支重点增强了 **阅读记录统计**、**AI 交互辅助**、**知识管理** 以及 **UI 体验** 等方面。

### 本项目核心拓展

- **详细阅读记录**：记录更细致的阅读信息，支持统计和导出，并支持[可视化解析](https://github.com/Jingshiro/LegadoRecord)
- **AI 助手**：引入大模型交互能力，支持工具调用，AI 可查询和管理书架等数据。支持独立入口，无需打开书籍即可与 AI 对话。
- **想法分享功能**：阅读时长按文字即可写下想法，并可生成分享卡片。支持将想法/笔记一键导出至 Obsidian（支持 REST API 或本地文件模式）。
- **阅读小票**：可在书籍首页和尾部显示小票，快速确认书籍的评分和阅读时长。
- **WebDAV 备份管理增强**：支持在本地对云端备份进行删除、重命名等操作。
- **读完/刷书标签**：记录书籍阅读状态（读完/N刷），并在书架上进行展示。
- **主题全量导出功能**：支持一键分享全部主题设置。
- **Web API 扩展**：Web 服务新增读书想法（增删改查）和阅读记录查询接口，支持 AI Agent 通过 HTTP 调用。
- **UI 优化**：对整体UI采用 MD3 设计语言进行优化。


---

## 开源许可

本项目基于 [GNU General Public License v3.0](https://www.gnu.org/licenses/gpl-3.0.html) 开源。

这意味着您可以自由使用、修改和分发本软件，但须遵守以下条件：
- 修改后的版本必须同样以 GPL-3.0 许可证发布
- 必须保留原始版权声明和许可证
- 必须公开修改后的源代码

详细条款请参阅 [GPL-3.0 完整文本](https://www.gnu.org/licenses/gpl-3.0.txt)。
---

## 感谢

> - org.jsoup:jsoup
> - cn.wanghaomiao:JsoupXpath
> - com.jayway.jsonpath:json-path
> - com.github.gedoor:rhino-android
> - com.squareup.okhttp3:okhttp
> - com.github.bumptech.glide:glide
> - org.nanohttpd:nanohttpd
> - org.nanohttpd:nanohttpd-websocket
> - cn.bingoogolapple:bga-qrcode-zxing
> - com.jaredrummler:colorpicker
> - org.apache.commons:commons-text
> - io.noties.markwon:core
> - io.noties.markwon:image-glide
> - com.hankcs:hanlp
> - com.positiondev.epublib:epublib-core
> - com.github.Moriafly:LyricViewX
> - io.github.rosemoe:editor
