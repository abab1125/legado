# Legado 写作台重构

## 项目背景
基于 Jingshiro/legado fork 的互动写小说 Android App。技术栈：Kotlin 2.3.10, XML+ViewBinding+Fragment+LiveData, Room 2.7.1, 无 Compose/Hilt/Navigation。

项目路径：`/Users/ma/开发/workSpace/legado/`

## 需求概述（3 张 UI 效果图已实现的风格）

### A. 阅读模式目录页增强（ChapterListFragment）
章节列表每个条目右侧加 ⋮ 按钮，点击弹出操作菜单，包含 8 项：
1. 插入下一章
2. 自动起名
3. 手动起名
4. 批量章节起名
5. 整理章节序号
6. 上移章节
7. 下移章节
8. 删除章节（红色）

### B. 底部 Tab 新增两个（和书架/发现/RSS/我的同级）
- 提示词（💡）
- 知识点（📋）

### C. 提示词和知识点改为全局共享，不再绑定 bookUrl

## 需要修改的文件

1. **ChapterListFragment.kt** — 加 ⋮ 按钮 + PopupMenu + 8 项操作
2. **ChapterListAdapter.kt** — registerListener 中加 moreBtn 点击
3. **item_chapter_list.xml** — 加 ⋮ 按钮
4. **KnowledgePoint.kt** — 去掉 bookUrl/chapterIndex，改为纯全局实体，保留 tags, category, sortOrder, createTime, updateTime
5. **KnowledgePointDao.kt** — 去掉所有 bookUrl 条件，全局查询
6. **DatabaseMigrations.kt** — 新增 migration（knowledge_points 表重建：去掉 bookUrl，数据保留到全局，chapterIndex 丢弃）
7. **MainActivity.kt** — 底部 Tab 改 6 个，处理发现/RSS 隐藏逻辑改为适配 6 tab
8. **main_bnv.xml** — 加 menu_prompt 和 menu_knowledge

## 需要新增的文件

9. **ui/write/PromptManageActivity.kt** — 提示词管理页
10. **ui/write/KnowledgeManageActivity.kt** — 知识点管理页
11. **res/layout/activity_prompt_manage.xml** — 顶部栏 + TabLayout + RecyclerView + FAB
12. **res/layout/activity_knowledge_manage.xml** — 顶部栏 + 搜索框 + RecyclerView + FAB
13. **res/layout/item_prompt.xml** — 卡片：标题 + 内容预览 + 标签 + 编辑/删除
14. **res/layout/item_knowledge.xml** — 卡片：标题 + 内容 + 关联章节 + 时间
15. **res/drawable/ic_prompt.xml** — 灯泡图标
16. **res/drawable/ic_knowledge.xml** — 文档/清单图标
17. **res/menu/menu_prompt_manage.xml** — 无（FAB 添加）
18. **res/menu/menu_chapter_context.xml** — 8 项操作菜单
19. **res/menu/main_bnv.xml** — 加 2 个 item

## 需要清理

20. **WriteBookActivity.kt** — 移除提示词/知识点代码
21. **WriteBookViewModel.kt** — 移除提示词/知识点代码
22. **PromptManageDialog.kt** — 删除
23. **KnowledgeManageDialog.kt** — 删除

## UI 细节

### 目录页 ⋮ 菜单
- 圆形 ⋮ 按钮 32dp，灰色背景
- 弹出 PopupWindow（白色圆角12dp，阴影）
- 菜单项：文字15dp + 图标14dp，删除红色
- 分隔线分组

### 提示词管理页（参考效果图 2）
- 顶部：返回 + "提示词管理" + +号（圆形紫色）
- Tab: 全部/角色设定/世界观/写作风格/大纲（TabLayout 横向滚动）
- 卡片：白底圆角12dp，padding14dp，标题15dp加粗，内容13dp灰色(3行截断)，蓝色标签 + 编辑/删除

### 知识点管理页（参考效果图 3）
- 顶部：返回 + "知识点" + +号
- 搜索框：圆角8dp，灰色背景
- 列表：时间线风格，左侧蓝色圆点12dp + 竖线，右侧卡片
- 卡片：标题 + 内容 + 关联章节（蓝色） + 时间

## 输出要求
- 每个文件完整代码不截断
- commit 但不 push
