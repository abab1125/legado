package io.legado.app.ui.book.read.ai

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.BaseFragment
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.BookHelp
import io.legado.app.lib.dialogs.alert
import io.legado.app.ui.book.read.ai.tool.AiToolDef
import io.legado.app.databinding.ActivityAiChatPanelBinding
import io.legado.app.ui.book.read.config.AiConfigDialog
import io.legado.app.ui.book.read.ai.ChapterPickerDialog
import io.legado.app.ui.book.read.ai.KnowledgePickerDialog
import io.legado.app.ui.book.read.ai.PromptPickerDialog
import io.legado.app.ui.write.PromptManageDialog
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.launch

/**
 * 写作页内嵌 AI 助手面板（从 AiChatActivity 抽出的可复用聊天 UI）。
 *
 * 与 AiChatActivity 的区别：
 * - 不自带 TitleBar，作为 Fragment 嵌入 ChapterEditorActivity 底部抽屉
 * - 书籍上下文通过 setBookContext 注入（写作页无 ReadBook 全局态）
 * - 观察 AiToolStatusBus 渲染灵犀式状态卡（读取章节 N 字 / 写入章节）
 * - 支持外部预填输入框（选中文字直问）
 */
class AiChatPanelFragment : BaseFragment(R.layout.activity_ai_chat_panel) {

    val binding by viewBinding(ActivityAiChatPanelBinding::bind)
    private val viewModel by lazy { AiChatViewModel(requireContext().applicationContext as android.app.Application) }

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

    /** 引用信息（@章节/@知识点/@提示词） */
    private val currentReferences = mutableListOf<ReferenceItem>()

    /** 注入的书籍上下文 */
    private var injectedBookUrl: String? = null
    private var injectedChapterIndex: Int = -1
    private var injectedChapterSize: Int = -1

    companion object {
        private const val ARG_BOOK_URL = "bookUrl"
        private const val ARG_CHAPTER_INDEX = "chapterIndex"
        private const val ARG_CHAPTER_SIZE = "chapterSize"

        fun newInstance(bookUrl: String?, chapterIndex: Int = -1, chapterSize: Int = -1): AiChatPanelFragment {
            return AiChatPanelFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_BOOK_URL, bookUrl)
                    putInt(ARG_CHAPTER_INDEX, chapterIndex)
                    putInt(ARG_CHAPTER_SIZE, chapterSize)
                }
            }
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        injectedBookUrl = arguments?.getString(ARG_BOOK_URL)
        injectedChapterIndex = arguments?.getInt(ARG_CHAPTER_INDEX, -1) ?: -1
        injectedChapterSize = arguments?.getInt(ARG_CHAPTER_SIZE, -1) ?: -1

        viewModel.setBookContext(injectedBookUrl, injectedChapterIndex, injectedChapterSize)

        initView()
        bindEvent()
        observeData()

        val currentChapter = if (injectedChapterIndex >= 0) (injectedChapterIndex + 1).toString() else "1"
        binding.etChapterStart.setText(currentChapter)
        binding.etChapterEnd.setText(currentChapter)

        val chapterSize = injectedChapterSize
        if (chapterSize <= 0) {
            binding.layoutChapterRange.visibility = View.GONE
        } else {
            viewModel.calculateWordCount(injectedBookUrl ?: "", 1, chapterSize)
        }

        viewModel.initMessages(
            if (injectedChapterIndex >= 0) injectedChapterIndex + 1 else 1,
            if (injectedChapterIndex >= 0) injectedChapterIndex + 1 else 1
        )
    }

    /** 外部设置当前章节索引（编辑器加载完成后注入） */
    fun setChapterContext(chapterIndex: Int) {
        injectedChapterIndex = chapterIndex
        viewModel.setBookContext(injectedBookUrl, chapterIndex, injectedChapterSize)
        // 同步章节范围输入框
        val cur = (chapterIndex + 1).toString()
        binding.etChapterStart.setText(cur)
        binding.etChapterEnd.setText(cur)
    }

    /** 外部预填输入框（选中文字直问） */
    fun prefillInput(text: String) {
        if (text.isNotBlank()) {
            binding.etInput.setText(text)
            binding.etInput.setSelection(text.length)
        }
    }

    private fun initView() {
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext()).apply {
            stackFromEnd = true
        }
        binding.recyclerView.adapter = adapter
    }

    private fun bindEvent() {
        binding.etInput.addTextChangedListener {
            updateWordCount()
        }

        binding.btnSend.setOnClickListener {
            if (viewModel.isGeneratingLiveData.value == true) {
                toastOnUi("正在生成中...")
                return@setOnClickListener
            }
            val text = binding.etInput.text.toString()
            if (text.isNotBlank()) {
                val start: Int
                val end: Int
                if (injectedChapterIndex >= 0) {
                    start = injectedChapterIndex + 1
                    end = injectedChapterIndex + 1
                } else {
                    start = binding.etChapterStart.text.toString().toIntOrNull() ?: 1
                    end = binding.etChapterEnd.text.toString().toIntOrNull() ?: 1
                }
                val refs = if (currentReferences.isNotEmpty()) currentReferences.toList() else null
                viewModel.sendMessage(text, start, end, refs)
                binding.etInput.setText("")
                currentReferences.clear()
                binding.layoutRefChips.removeAllViews()
                binding.layoutRefChips.visibility = View.GONE
            }
        }

        binding.btnVoiceDesign.setOnClickListener {
            binding.etInput.setText(AiChatViewModel.VOICE_DESIGN_PROMPT)
            binding.etInput.setSelection(binding.etInput.text.length)
        }

        binding.btnRefChapter.setOnClickListener {
            val bookUrl = injectedBookUrl ?: run {
                toastOnUi("请先打开一本书")
                return@setOnClickListener
            }
            val dialog = ChapterPickerDialog.newInstance(bookUrl)
            dialog.setOnSelectedListener { ref -> appendRefTag(ref) }
            dialog.show(parentFragmentManager, "chapter_picker")
        }

        binding.btnRefKnowledge.setOnClickListener {
            val dialog = KnowledgePickerDialog()
            dialog.setOnSelectedListener { ref -> appendRefTag(ref) }
            dialog.show(parentFragmentManager, "knowledge_picker")
        }

        binding.btnRefPrompt.setOnClickListener {
            val dialog = PromptPickerDialog()
            dialog.setOnSelectedListener { ref -> appendRefTag(ref) }
            dialog.show(parentFragmentManager, "prompt_picker")
        }
    }

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

    private fun addReference(ref: ReferenceItem) {
        currentReferences.add(ref)
        val density = resources.displayMetrics.density
        val chip = LinearLayout(requireContext()).apply {
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
        val label = TextView(requireContext()).apply {
            text = ref.title
            textSize = 11f
            val color = when (ref.type) {
                "chapter" -> android.graphics.Color.parseColor("#6aaaff")
                "knowledge" -> android.graphics.Color.parseColor("#6acc6a")
                "prompt" -> android.graphics.Color.parseColor("#cc6acc")
                else -> android.graphics.Color.GRAY
            }
            setTextColor(color)
        }
        chip.addView(label)
        val closeBtn = ImageView(requireContext()).apply {
            setImageResource(R.drawable.ic_baseline_close)
            val size = (density * 12).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginStart = (density * 4).toInt()
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
            setColorFilter(android.graphics.Color.parseColor("#888888"))
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
        val start = binding.etChapterStart.text.toString().toIntOrNull()
        val end = binding.etChapterEnd.text.toString().toIntOrNull()
        val chapterSize = injectedChapterSize
        if (start != null && end != null && start > 0 && end > 0 &&
            injectedBookUrl != null && chapterSize > 0
        ) {
            viewModel.calculateWordCount(injectedBookUrl ?: "", start, end)
        }
    }

    private fun showEditDialog(displayPosition: Int, currentContent: String) {
        val editText = android.widget.EditText(requireContext()).apply {
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
        viewModel.messagesLiveData.observe(viewLifecycleOwner) { msgs ->
            val displayMsgs = msgs.filter { it.role != "system" && it.role != "tool" }
            adapter.submitList(displayMsgs)
            if (displayMsgs.isNotEmpty()) {
                binding.recyclerView.scrollToPosition(displayMsgs.size - 1)
            }
        }

        viewModel.wordCountLiveData.observe(viewLifecycleOwner) { count ->
            val countStr = if (count >= 10000) String.format("%.1f万", count / 10000f) else count.toString()
            binding.tvWordCount.text = "字数: $countStr"
            binding.tvWordCount.setTextColor(
                if (count > 50000) android.graphics.Color.RED else android.graphics.Color.parseColor("#888888")
            )
        }

        viewModel.isGeneratingLiveData.observe(viewLifecycleOwner) { isGenerating ->
            binding.btnSend.setIconResource(
                if (isGenerating) R.drawable.ic_stop_black_24dp else R.drawable.ic_send
            )
        }

        viewModel.statusLiveData.observe(viewLifecycleOwner) { status ->
            val indicator = binding.llStatusIndicator
            val text = binding.tvStatusText
            val spinner = binding.statusSpinner
            when (status) {
                AiChatViewModel.STATUS_IDLE -> indicator.visibility = View.GONE
                else -> {
                    indicator.visibility = View.VISIBLE
                    spinner.visibility = View.VISIBLE
                    text.text = when (status) {
                        AiChatViewModel.STATUS_SENDING -> "发送请求中…"
                        AiChatViewModel.STATUS_THINKING -> "AI 思考中…"
                        AiChatViewModel.STATUS_TOOL_RUNNING -> "正在执行操作…"
                        else -> ""
                    }
                }
            }
        }

        // 灵犀式状态卡
        AiToolStatusBus.toolActivityLiveData.observe(viewLifecycleOwner) { activity ->
            binding.layoutToolActivity.visibility = View.VISIBLE
            binding.tvToolActivity.text = when (activity.kind) {
                "tool_start" -> "⏳ ${activity.detail}"
                "tool_end" -> "✓ ${activity.detail}"
                else -> activity.detail
            }
        }

        // 写操作确认弹窗
        viewModel.batchConfirmationLiveData.observe(viewLifecycleOwner) { request ->
            if (request == null) return@observe
            alert(io.legado.app.R.string.ai_confirm_title) {
                setMessage(request.descriptions.joinToString("\n"))
                yesButton { viewModel.confirmBatchAction(true) }
                noButton { viewModel.confirmBatchAction(false) }
            }
        }
    }
}
