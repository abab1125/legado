**2026/05/28**

- 书架网格布局全面重构：去掉MaterialCardView，封面即卡片，书名+进度条叠在封面上
- 新增阅读进度条：封面底部3px自绘进度条，使用用户强调色
- 书架Tab栏、背景色、间距全面对齐MD3设计稿
- 书籍详情页重构：封面居中，作者/来源移入信息行，间距替代分割线，简介使用用户主题色
- 阅读页底部菜单改为MD3浮层MaterialCardView，四角圆角+elevation阴影
- 阅读页顶部工具栏改为MD3悬浮卡片，两行结构（书名+功能按钮/章节信息）
- 新增顶部工具栏：刷新、下载、更多按钮，更多菜单含换源/书签/翻页动画等
- 弹窗统一16dp圆角，BaseDialogFragment支持圆角背景
- 视频播放器弹窗左侧圆角
- 主题dialogCornerRadius统一设置16dp

**2026/05/18**

- 修复AI助手界面改为MD风格后启动崩溃的问题
- 修复MaterialCardView的strokeColor使用了drawable属性(?attr/dividerHorizontal)而非颜色导致在Android 16上崩溃
- 修复BaseActivity运行时覆盖Material主题导致MaterialComponents组件样式无法解析
- 修复引用MaterialComponents内部私有样式ShapeAppearanceOverlay导致的兼容性问题

**2022/10/02**

- 更新cronet: 106.0.5249.79
- 正文选择菜单朗读按钮长按可切换朗读选择内容和从选择开始处一直朗读
- 源编辑输入框设置最大行数12,在行数特别多的时候更容易滚动到其它输入
- 修复某些情况下无法搜索到标题的bug，净化规则较多的可能会降低搜索速度 by Xwite
- 修复文件类书源换源后阅读bug by Xwite
- Cronet 支持DnsHttpsSvcb by g2s20150909
- 修复web进度同步问题 by 821938089
- 启用混淆以减小app大小 有bug请带日志反馈
- 其它一些优化
