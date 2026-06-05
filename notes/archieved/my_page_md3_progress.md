# "我的"页面 MD3 改造进度

## 已完成

1. ✅ `iconnew.png` 已复制到 `app/src/main/res/drawable/iconnew.png`
2. ✅ 创建了 `app/src/main/res/drawable/bg_quick_action.xml` (快捷按钮背景，MD3 圆角+波纹)

## 待完成

### 3. 重写 `fragment_my_config.xml`
- 移除 PreferenceFragment 容器
- 改为自定义布局：TitleBar + 图标区 + 快捷按钮行 + 设置列表

### 4. 重写 `MyFragment.kt`
- 移除内部 `MyPreferenceFragment` 类
- 改为直接处理点击事件
- 实现功能：
  - **备份恢复**：短按 → `CloudBackupActivity`，长按 → 本地备份流程
  - **WebDAV**：弹出对话框设置 URL/账号/密码
  - **Web 服务**：切换按钮，开/关 `WebService`
  - **阅读记录**：打开 `WebViewActivity` 加载 `https://myst423.shop/recorder/`

### 5. 设置列表项
- 保留原有 16 个设置项的跳转逻辑
- 使用 RecyclerView 或 LinearLayout 构建列表

## 关键文件参考

| 文件 | 用途 |
|------|------|
| `MyFragment.kt` | 当前实现，需重写 |
| `BackupConfigFragment.kt` | 备份逻辑参考 (backup/restore 方法) |
| `CloudBackupActivity.kt` | 云端备份管理页面 |
| `WebViewActivity.kt` | 内置浏览器，intent extra: `url` |
| `WebService.kt` | start(context) / stop(context) |
| `pref_config_backup.xml` | WebDav 配置项参考 |

## MD3 样式规范

- 卡片圆角：12dp (小组件) / 16dp (中等组件)
- 卡片背景：`@color/background_card`
- 波纹效果：`?android:attr/selectableItemBackground`
- 颜色：`colorPrimary`, `colorSurface`, `colorOnSurface`
