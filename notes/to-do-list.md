# 待办事项清单

> 基于 notes/ 目录下所有功能开发记录，对比代码实现状态整理。
> 更新时间：2026-06-05

---

## ✅ 已完成

| 功能 | 对应文档 | 说明 |
|------|----------|------|
| 云端备份管理 | `cloud_backup_management_plan.md` | CloudBackupActivity/ViewModel/Adapter 全部实现，支持列表、删除、重命名、恢复 |
| 阅读记录联动删除 | `implementation_plan_read_record.md` | ReadRecordActivity 已实现删除/清空时同步删除详细阅读记录 |
| AI 助手核心功能 | `project_ai_assistant.md` | 16个工具（8只读+8写操作）、记忆系统、独立入口全部完成 |
| AI 助手优化 | `ai_assistant_optimization_plan.md` | 独立模式判断修复、记忆保存/会话恢复已实现 |
| 想法导出 Obsidian | `thought_obsidian_export_plan.md` | ObsidianExportDialog + ThoughtObsidianExporter 全部完成，支持 REST API 和本地文件两种方式 |
| 藏书票 | `reading_receipt.md` | BookplateDrawer 已实现，含阅读凭证绘制、评分交互、完读触发 |
| "我的"页面 MD3 改造 | `my_page_md3_progress.md` | MyFragment 已重写为自定义布局，移除 PreferenceFragment |
| 阅读页二级弹窗统一 | `pure-sniffing-papert.md`（第一部分） | 自动翻页/朗读/界面/设置弹窗改为 MD3 MaterialCardView 卡片 |
| 写想法弹窗重设计 | `pure-sniffing-papert.md`（第二部分） | 底部弹窗风格，含拖拽条、标题、输入区、下划线样式按钮 |
| 想法下划线样式自定义 | `pure-sniffing-papert.md`（第三部分） | 支持实线/虚线/点线、粗细调节、颜色取色器、每条笔记独立保存 |
| AI 工具：get_book_content | `legado_new_tools_guide.md` | ToolRouter 中已实现 |
| AI 工具：search_online_book | `legado_new_tools_guide.md` | ToolRouter 中已实现 |
| AI 工具：save_book_progress | `legado_new_tools_guide.md` | ToolRouter 中已实现 |
| AI 工具：rate_book | `legado_new_tools_guide.md` | ToolRouter 中已实现 |
| AI 工具：mark_book_status | `legado_new_tools_guide.md` | ToolRouter 中已实现 |
| AI 工具：set_book_note | `legado_new_tools_guide.md` | BookThoughtController.saveBookThought 已实现 |

---

## ❌ 未完成

### Material Design 迁移（`material_design_migration_plan.md`）

| 阶段 | 内容 | 状态 |
|------|------|------|
| Phase 2 | 自定义 View 迁移（16个 AppCompat 基类替换为 Material/标准基类） | ❌ 未开始 |
| Phase 3a | TextInputLayout 替换为 Material 版本（148处） | ❌ 未开始 |
| Phase 3b | Toolbar → MaterialToolbar（31处） | ❌ 未开始 |
| Phase 3c | AppCompatImageView → ImageView（73处） | ❌ 未开始 |
| Phase 3d | CardView → MaterialCardView（~26处） | ❌ 未开始 |
| Phase 3e | AppCompatSpinner 自动升级确认 | ❌ 未开始 |
| Phase 4 | 样式细节调优（色彩系统、组件样式统一、ripple、Shape theming） | ❌ 未开始 |
| Phase 5 | 进阶优化（Material3、Dynamic Color、Material Motion、BottomSheet） | ❌ 未开始 |

### AI 工具（`legado_new_tools_guide.md`）— 管理闭环

| 工具 | 功能 | 状态 |
|------|------|------|
| `get_replace_rules` | 获取文本替换规则列表 | ❌ 未实现 |
| `save_replace_rule` | 创建/修改替换规则 | ❌ 未实现 |
| `delete_replace_rule` | 删除替换规则 | ❌ 未实现 |
| `save_book_source` | 导入书源（JSON/URL） | ❌ 未实现 |
| `manage_webdav` | WebDAV 备份管理（列出/删除/恢复/重命名） | ❌ 未实现 |

### AI 工具（`legado_new_tools_guide.md`）— 知识闭环

| 工具 | 功能 | 状态 |
|------|------|------|
| `get_thoughts` | 获取读书想法列表 | ❌ 未实现 |
| `export_to_obsidian` | 想法导出到 Obsidian（AI 工具入口） | ❌ 未实现 |
| `get_detailed_reading_record` | 获取详细阅读记录（按天/书/时间段） | ❌ 未实现 |

### 其他

| 功能 | 说明 | 状态 |
|------|------|------|
| 阅读记录排除订阅源 | ReadRssActivity/VideoPlayerActivity 中排除 RSS 源的详细记录（`implementation_plan_read_record.md` 待确认问题） | ⚠️ 待确认 |
