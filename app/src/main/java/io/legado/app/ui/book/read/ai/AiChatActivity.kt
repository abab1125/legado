package io.legado.app.ui.book.read.ai

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import androidx.appcompat.app.AlertDialog
import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.lib.dialogs.alert
import io.legado.app.databinding.ActivityAiChatBinding
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.config.AiConfigDialog
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.utils.applyBackgroundTint

class AiChatActivity : BaseActivity<ActivityAiChatBinding>(false) {

    override val binding by viewBinding(ActivityAiChatBinding::inflate)
    internal val viewModel by viewModels<AiChatViewModel>()
    private val adapter by lazy {
        ChatAdapter(
            onEditMessage = { displayPosition, currentContent ->
                showEditDialog(displayPosition, currentContent)
            },
            onDeleteMessage = { displayPosition ->
                viewModel.deleteMessageAt(displayPosition)
            }
        )
    }

    /** 当前引用的参考信息列表 */
    private val currentReferences = mutableListOf<ReferenceItem>()

    /** 是否为独立模式（从"我的"页面进入，无书籍上下文） */
    private val isStandalone: Boolean get() = intent.getBooleanExtra("isStandalone", false) || ReadBook.book == null

    override fun initTheme() {
        // 保持 Material 主题，不允许 BaseActivity 覆盖为 AppCompat 主题
        setTheme(R.style.AppTheme_Material)
        window.decorView.applyBackgroundTint(backgroundColor)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initView()
        bindEvent()
        observeData()
        setupKeyboardAdjustment()

        // 从阅读器传过来的选中文本，预填到输入框
        val incomingText = intent.getStringExtra("selectedText")

        if (isStandalone) {
            // 独立模式：隐藏章节选择区域和预置提示词栏，直接初始化
            binding.layoutChapterRange.visibility = View.GONE
            binding.layoutPromptPresets.visibility = View.GONE
            binding.titleBar.title = getString(R.string.ai_assistant)
            viewModel.initMessages(0, 0)
        } else {
            val currentChapter = (ReadBook.durChapterIndex + 1).toString()
            binding.etChapterStart.setText(currentChapter)
            binding.etChapterEnd.setText(currentChapter)
            // 标题显示书名，让用户明确当前AI关联的书籍
            val bookName = ReadBook.book?.name
            if (!bookName.isNullOrBlank()) {
                binding.titleBar.title = bookName
            } else {
                binding.titleBar.title = getString(R.string.ai_assistant)
            }
            viewModel.initMessages(
                ReadBook.durChapterIndex + 1,
                ReadBook.durChapterIndex + 1
            )
            updateWordCount()
        }

        // 预填选中文本到输入框
        if (!incomingText.isNullOrBlank()) {
            binding.etInput.setText(incomingText)
            binding.etInput.setSelection(incomingText.length)
        }
    }

    private fun setupKeyboardAdjustment() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val imeHeight = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val navBarHeight = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val bottomPadding = if (imeHeight > navBarHeight) imeHeight else navBarHeight
            binding.root.setPadding(0, 0, 0, bottomPadding)
            insets
        }
    }

    private fun initView() {
        binding.recyclerView.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        binding.recyclerView.adapter = adapter

        // 底部输入栏使用主题底部操作栏颜色
        binding.bottomBar.setCardBackgroundColor(bottomBackground)
    }

    private fun bindEvent() {
        val textWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updateWordCount()
            }
        }
        binding.etChapterStart.addTextChangedListener(textWatcher)
        binding.etChapterEnd.addTextChangedListener(textWatcher)

        binding.btnSend.setOnClickListener {
            if (viewModel.isGeneratingLiveData.value == true) {
                toastOnUi("正在生成中...")
                return@setOnClickListener
            }
            val text = binding.etInput.text.toString()
            if (text.isNotBlank()) {
                val start: Int
                val end: Int
                if (isStandalone) {
                    start = 0
                    end = 0
                } else {
                    start = binding.etChapterStart.text.toString().toIntOrNull()
                        ?: (ReadBook.durChapterIndex + 1)
                    end = binding.etChapterEnd.text.toString().toIntOrNull()
                        ?: (ReadBook.durChapterIndex + 1)
                }
                val refs = if (currentReferences.isNotEmpty()) currentReferences.toList() else null
                viewModel.sendMessage(text, start, end, refs)
                binding.etInput.setText("")
                // 发送后清空引用
                currentReferences.clear()
                binding.layoutRefChips.removeAllViews()
                binding.layoutRefChips.visibility = View.GONE
            }
        }

        // 声线设计按钮：预填提示词到输入框
        binding.btnVoiceDesign.setOnClickListener {
            binding.etInput.setText(AiChatViewModel.VOICE_DESIGN_PROMPT)
            binding.etInput.setSelection(binding.etInput.text.length)
        }

        // ===== @引用按钮 =====
        binding.btnRefChapter.setOnClickListener {
            val bookUrl = ReadBook.book?.bookUrl ?: run {
                toastOnUi("请先打开一本书")
                return@setOnClickListener
            }
            val dialog = ChapterPickerDialog.newInstance(bookUrl)
            dialog.setOnSelectedListener { ref -> appendRefTag(ref) }
            dialog.show(supportFragmentManager, "chapter_picker")
        }

        binding.btnRefKnowledge.setOnClickListener {
            val dialog = KnowledgePickerDialog()
            dialog.setOnSelectedListener { ref -> appendRefTag(ref) }
            dialog.show(supportFragmentManager, "knowledge_picker")
        }

        binding.btnRefPrompt.setOnClickListener {
            val dialog = PromptPickerDialog()
            dialog.setOnSelectedListener { ref -> appendRefTag(ref) }
            dialog.show(supportFragmentManager, "prompt_picker")
        }

        binding.btnRefHistory.setOnClickListener {
            toastOnUi("引用历史功能开发中")
        }
    }

    /**
     * 追加 @引用标签到输入框，并记录引用信息
     */
    private fun appendRefTag(ref: ReferenceItem) {
        addReference(ref)
        val tag = "@${ref.title}"
        val input = binding.etInput.text
        if (input.isNotEmpty() && !input.endsWith(" ")) {
            binding.etInput.append(" $tag ")
        } else {
            binding.etInput.append("$tag ")
        }
    }

    /**
     * 添加一个引用：显示可删除芯片并存储 ReferenceItem
     */
    private fun addReference(ref: ReferenceItem) {
        currentReferences.add(ref)
        val density = resources.displayMetrics.density

        val chip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(
                (density * 8).toInt(), (density * 3).toInt(),
                (density * 4).toInt(), (density * 3).toInt()
            )
            setBackgroundResource(R.drawable.bg_search_round)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins(0, 0, (density * 4).toInt(), 0)
            layoutParams = lp
        }

        // 名称
        val label = TextView(this).apply {
            text = ref.title
            textSize = 11f
            val color = when (ref.type) {
                "chapter" -> Color.parseColor("#6aaaff")
                "knowledge" -> Color.parseColor("#6acc6a")
                "prompt" -> Color.parseColor("#cc6acc")
                else -> Color.GRAY
            }
            setTextColor(color)
        }
        chip.addView(label)

        // × 删除按钮
        val closeBtn = ImageView(this).apply {
            setImageResource(R.drawable.ic_baseline_close)
            val size = (density * 12).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginStart = (density * 4).toInt()
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
            setColorFilter(Color.parseColor("#888888"))
            contentDescription = "取消引用"
            setOnClickListener {
                val parent = binding.layoutRefChips
                val idx = parent.indexOfChild(chip)
                if (idx >= 0 && idx < currentReferences.size) {
                    currentReferences.removeAt(idx)
                }
                parent.removeView(chip)
                if (currentReferences.isEmpty()) {
                    parent.visibility = View.GONE
                }
            }
        }
        chip.addView(closeBtn)

        binding.layoutRefChips.addView(chip)
        binding.layoutRefChips.visibility = View.VISIBLE
    }

    private fun updateWordCount() {
        if (isStandalone) return
        val start = binding.etChapterStart.text.toString().toIntOrNull()
        val end = binding.etChapterEnd.text.toString().toIntOrNull()
        val bookUrl = ReadBook.book?.bookUrl
        val chapterSize = ReadBook.chapterSize
        if (start != null && end != null && start > 0 && end > 0 && bookUrl != null && chapterSize > 0) {
            viewModel.calculateWordCount(bookUrl, start, end)
        }
    }

    private fun showEditDialog(displayPosition: Int, currentContent: String) {
        val editText = android.widget.EditText(this).apply {
            setText(currentContent)
            setSelection(currentContent.length)
            setPadding(48, 32, 48, 16)
            isSingleLine = false
            minLines = 3
            gravity = android.view.Gravity.TOP
        }
        alert("编辑消息") {
            setCustomView(editText)
            yesButton {
                val newContent = editText.text.toString()
                if (newContent.isNotBlank()) {
                    viewModel.updateMessageAt(displayPosition, newContent)
                }
            }
            noButton()
        }
    }

    private fun observeData() {
        viewModel.messagesLiveData.observe(this) { msgs ->
            val displayMsgs = msgs.filter { it.role != "system" && it.role != "tool" }
            adapter.submitList(displayMsgs)
            if (displayMsgs.isNotEmpty()) {
                binding.recyclerView.scrollToPosition(displayMsgs.size - 1)
            }
        }

        viewModel.wordCountLiveData.observe(this) { count ->
            if (isStandalone) return@observe
            val countStr = if (count >= 10000) String.format("%.1f万", count / 10000f) else count.toString()
            binding.tvWordCount.text = "字数: $countStr"
            if (count > 50000) {
                binding.tvWordCount.setTextColor(Color.RED)
            } else {
                binding.tvWordCount.setTextColor(Color.parseColor("#888888"))
            }
        }

        viewModel.isGeneratingLiveData.observe(this) { isGenerating ->
            if (isGenerating) {
                binding.btnSend.setIconResource(R.drawable.ic_stop_black_24dp)
            } else {
                binding.btnSend.setIconResource(R.drawable.ic_send)
            }
        }

        viewModel.confirmationLiveData.observe(this) { request ->
            if (request == null) return@observe
            alert(R.string.ai_confirm_title) {
                setMessage(request.description)
                yesButton {
                    viewModel.confirmAction(true)
                }
                noButton {
                    viewModel.confirmAction(false)
                }
            }
        }

        viewModel.batchConfirmationLiveData.observe(this) { request ->
            if (request == null) return@observe
            showBatchConfirmationDialog(request.descriptions) { confirmed ->
                viewModel.confirmBatchAction(confirmed)
            }
        }
    }

    /**
     * 展示批量确认对话框：用可滚动列表展示所有待执行的操作。
     */
    private fun showBatchConfirmationDialog(
        descriptions: List<String>,
        callback: (Boolean) -> Unit
    ) {
        val dp = resources.displayMetrics.density

        // 外层容器
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (20 * dp).toInt(), (12 * dp).toInt(),
                (20 * dp).toInt(), (4 * dp).toInt()
            )
        }

        // 头部提示
        val header = TextView(this).apply {
            text = "以下 ${descriptions.size} 个操作将被执行，请确认是否继续："
            textSize = 14f
            setTextColor(Color.parseColor("#888888"))
            setPadding(0, 0, 0, (8 * dp).toInt())
        }
        container.addView(header)

        // 分隔线
        container.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (1 * dp).toInt()
            )
            setBackgroundColor(Color.parseColor("#22888888"))
        })

        // 操作列表内容
        val listLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (4 * dp).toInt(), 0, 0)
        }
        descriptions.forEachIndexed { index, desc ->
            val item = TextView(this).apply {
                text = "  ${index + 1}. $desc"
                textSize = 15f
                setPadding(
                    (4 * dp).toInt(), (8 * dp).toInt(),
                    (4 * dp).toInt(), (8 * dp).toInt()
                )
            }
            listLayout.addView(item)
            // 除最后一项外添加小分隔线
            if (index < descriptions.size - 1) {
                listLayout.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, (1 * dp).toInt()
                    ).apply { setMargins((4 * dp).toInt(), 0, (4 * dp).toInt(), 0) }
                    setBackgroundColor(Color.parseColor("#11888888"))
                })
            }
        }

        // 如果内容过长则包装在 ScrollView 内
        val maxHeightPx = (resources.displayMetrics.heightPixels * 0.45).toInt()
        val scrollView = ScrollView(this).apply {
            addView(listLayout)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            // 限制最大高度避免对话框过长
            post {
                if (height > maxHeightPx) {
                    layoutParams = layoutParams.also {
                        (it as LinearLayout.LayoutParams).height = maxHeightPx
                    }
                }
            }
        }
        container.addView(scrollView)

        alert("确认批量整理") {
            setCustomView(container)
            positiveButton("确认执行") {
                callback(true)
            }
            negativeButton("全部拒绝") {
                callback(false)
            }
        }
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.ai_chat_menu, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_ai_clear -> {
                if (viewModel.isGeneratingLiveData.value == true) {
                    toastOnUi("正在生成中，请稍候...")
                    return true
                }
                alert("清空对话") {
                    setMessage("确定要清空当前全部对话记录吗？")
                    yesButton {
                        viewModel.clearMessages()
                    }
                    noButton { }
                }
                return true
            }
            R.id.menu_ai_settings -> {
                showDialogFragment(AiConfigDialog())
                return true
            }
            R.id.menu_ai_summarize -> {
                val start = if (isStandalone) 0 else binding.etChapterStart.text.toString().toIntOrNull()
                    ?: (ReadBook.durChapterIndex + 1)
                val end = if (isStandalone) 0 else binding.etChapterEnd.text.toString().toIntOrNull()
                    ?: (ReadBook.durChapterIndex + 1)
                viewModel.saveSession(start, end)
                return true
            }
            R.id.menu_ai_extract_characters -> {
                showExtractCharacterDialog()
                return true
            }
        }
        return super.onCompatOptionsItemSelected(item)
    }

    /**
     * 展示提取角色对话框：让用户选择要提取的章节（多选勾选），执行并保存结果
     */
    private fun showExtractCharacterDialog() {
        if (isStandalone) {
            toastOnUi("请在阅读书籍时使用该功能")
            return
        }
        val book = ReadBook.book ?: run {
            toastOnUi("请先打开一本书")
            return
        }
        val bookUrl = book.bookUrl
        val bookName = book.name.ifBlank { "未命名" }
        val totalChapters = ReadBook.chapterSize
        val dp = resources.displayMetrics.density

        // 获取所有章节标题列表
        val chapterList = viewModel.getChapterTitles(bookUrl)
        if (chapterList.isEmpty()) {
            toastOnUi("未能读取章节列表")
            return
        }

        // 多选章节的 CheckBox 列表
        val checkedChapters = BooleanArray(chapterList.size) { false }
        // 默认选中前 20 章
        val defaultSelect = minOf(20, chapterList.size)
        for (i in 0 until defaultSelect) checkedChapters[i] = true

        val scrollContainer = ScrollView(this)
        val contentLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (4 * dp).toInt())
        }

        // 说明文字
        contentLayout.addView(TextView(this).apply {
            text = "勾选要提取角色的章节（建议选 10-30 章，提取结果自动保存到知识库）"
            textSize = 13f
            setTextColor(android.graphics.Color.parseColor("#888888"))
            setPadding(0, 0, 0, (8 * dp).toInt())
        })

        // 全选 / 取消全选 快捷操作
        val quickRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, (8 * dp).toInt())
        }
        for ((label, action) in listOf(
            "全选" to { for (i in checkedChapters.indices) checkedChapters[i] = true },
            "取消" to { for (i in checkedChapters.indices) checkedChapters[i] = false },
            "前20章" to {
                for (i in checkedChapters.indices) checkedChapters[i] = i < 20
            }
        )) {
            val btn = TextView(this).apply {
                text = label
                textSize = 12f
                setPadding((12 * dp).toInt(), (4 * dp).toInt(), (12 * dp).toInt(), (4 * dp).toInt())
                setTextColor(ContextCompat.getColor(context, R.color.accent))
                setBackgroundResource(R.drawable.bg_filter_inactive)
                isClickable = true
                setOnClickListener {
                    action()
                    refreshCheckboxStates(contentLayout, checkedChapters, chapterList)
                }
            }
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.marginEnd = (8 * dp).toInt()
            quickRow.addView(btn, lp)
        }
        contentLayout.addView(quickRow)

        // 章节勾选列表
        for (i in chapterList.indices) {
            val cb = androidx.appcompat.widget.AppCompatCheckBox(this).apply {
                text = chapterList[i]
                textSize = 13f
                isChecked = checkedChapters[i]
                setPadding((8 * dp).toInt(), (4 * dp).toInt(), (8 * dp).toInt(), (4 * dp).toInt())
                setOnCheckedChangeListener { _, isChecked ->
                    checkedChapters[i] = isChecked
                }
            }
            contentLayout.addView(cb)
        }

        scrollContainer.addView(contentLayout)

        // 进度文字（直接放在 dialog 的 custom view 底部）
        val progressText = TextView(this).apply {
            textSize = 13f
            setTextColor(android.graphics.Color.parseColor("#888888"))
            gravity = android.view.Gravity.CENTER
            setPadding(0, (8 * dp).toInt(), 0, (4 * dp).toInt())
        }

        // 外层容器包含章节列表 + 进度文字
        val outerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        outerLayout.addView(scrollContainer, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        outerLayout.addView(progressText)

        val selectedCount: () -> Int = { checkedChapters.count { it } }
        var dialog: AlertDialog? = null
        dialog = alert("提取小说角色（已选 ${selectedCount()} 章）") {
            setCustomView(outerLayout)
            yesButton {
                val selected = checkedChapters.indices.filter { checkedChapters[it] }
                if (selected.isEmpty()) {
                    toastOnUi("请至少选择一个章节")
                    return@yesButton
                }

                // 禁用按钮
                dialog?.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.isEnabled = false
                dialog?.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)?.isEnabled = false

                lifecycleScope.launch {
                    try {
                        val roles = viewModel.extractCharacters(
                            bookUrl = bookUrl,
                            bookName = bookName,
                            chapterIndexes = selected,
                            onProgress = { cur, tot, phase ->
                                runOnUiThread {
                                    progressText.text = if (tot > 0) "$phase ($cur/$tot)" else phase
                                }
                            }
                        )
                        runOnUiThread {
                            if (roles.isEmpty()) {
                                toastOnUi("未提取到任何角色，请尝试扩大章节范围")
                                dialog?.dismiss()
                            } else {
                                val names = roles.joinToString("、") { it.name }
                                toastOnUi("提取到 ${roles.size} 个角色：$names")
                                dialog?.dismiss()
                                alert("提取结果") {
                                    setMessage(
                                        roles.joinToString("\n\n") { "• ${it.name}\n  ${it.description}" }
                                    )
                                    okButton()
                                }
                            }
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            toastOnUi("提取失败：${e.message}")
                        }
                    }
                }
            }
            noButton()
        }
    }

    private fun refreshCheckboxStates(
        layout: LinearLayout,
        checked: BooleanArray,
        names: List<String>
    ) {
        var cbIdx = 0
        for (i in 0 until layout.childCount) {
            val child = layout.getChildAt(i)
            if (child is androidx.appcompat.widget.AppCompatCheckBox) {
                if (cbIdx < checked.size) {
                    child.isChecked = checked[cbIdx]
                    cbIdx++
                }
            }
        }
    }
