package io.legado.app.ui.write

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.FrameLayout
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.databinding.ActivityChapterEditorBinding
import io.legado.app.help.book.BookHelp
import io.legado.app.ui.book.read.ai.AiChatPanelFragment
import io.legado.app.ui.book.read.ai.AiToolStatusBus
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import androidx.activity.viewModels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChapterEditorActivity :
    VMBaseActivity<ActivityChapterEditorBinding, WriteBookViewModel>() {

    override val binding by viewBinding(ActivityChapterEditorBinding::inflate)
    override val viewModel by viewModels<WriteBookViewModel>()

    private var book: Book? = null
    private var chapter: BookChapter? = null
    private var isModified = false
    private var contentLoaded = ""
    private var currentBookUrl: String = ""
    private var currentChapterUrl: String = ""

    /** 内嵌 AI 面板（底部抽屉） */
    private var aiPanel: AiChatPanelFragment? = null
    private var aiPanelExpanded = false

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        val bookUrl = intent.getStringExtra("bookUrl") ?: run {
            toastOnUi("缺少 bookUrl")
            finish()
            return
        }
        val chapterUrl = intent.getStringExtra("chapterUrl") ?: run {
            toastOnUi("缺少 chapterUrl")
            finish()
            return
        }
        currentBookUrl = bookUrl
        currentChapterUrl = chapterUrl

        setupAiPanel()
        loadData(bookUrl, chapterUrl)
    }

    /** 初始化底部 AI 面板（默认收起为输入条） */
    private fun setupAiPanel() {
        aiPanel = AiChatPanelFragment.newInstance(currentBookUrl, -1, -1)
        supportFragmentManager.beginTransaction()
            .replace(R.id.ai_panel_container, aiPanel!!)
            .commitNow()
        // 默认收起为输入条
        collapseAiPanel()
        // 点击 handle 展开，点击 scrim 收起
        binding.aiPanelHandle.setOnClickListener { toggleAiPanel() }
        binding.aiPanelScrim.setOnClickListener { if (aiPanelExpanded) toggleAiPanel() }
    }

    /** 收起 AI 面板为输入条高度 */
    private fun collapseAiPanel() {
        aiPanelExpanded = false
        binding.aiPanelContainer.layoutParams.height = (resources.displayMetrics.density * 56).toInt()
        binding.aiPanelScrim.visibility = android.view.View.GONE
        binding.aiPanelContainer.requestLayout()
    }

    /** 展开 / 收起 AI 面板 */
    private fun toggleAiPanel() {
        aiPanelExpanded = !aiPanelExpanded
        val dp = resources.displayMetrics.density
        val collapsed = (dp * 56).toInt()
        val expanded = (resources.displayMetrics.heightPixels * 0.6).toInt()
        binding.aiPanelContainer.layoutParams.height = if (aiPanelExpanded) expanded else collapsed
        binding.aiPanelScrim.visibility = if (aiPanelExpanded) android.view.View.VISIBLE else android.view.View.GONE
        binding.aiPanelContainer.requestLayout()
    }

    /** 选中文字直问：把编辑器选区填入 AI 面板输入框 */
    private fun askAiWithSelection() {
        val selStart = binding.editor.selectionStart
        val selEnd = binding.editor.selectionEnd
        val text = binding.editor.text?.toString() ?: ""
        val selected = if (selStart >= 0 && selEnd > selStart) {
            text.substring(selStart, selEnd)
        } else ""
        if (selected.isBlank()) {
            toastOnUi("请先选中一段文字")
            return
        }
        if (!aiPanelExpanded) toggleAiPanel()
        aiPanel?.prefillInput("请基于以下选中内容进行修改/续写：\n「$selected」")
    }

    private fun loadData(bookUrl: String, chapterUrl: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val b = appDb.bookDao.getBook(bookUrl)
            val c = appDb.bookChapterDao.getChapterByUrl(bookUrl, chapterUrl)

            if (b == null || c == null) {
                withContext(Dispatchers.Main) {
                    toastOnUi("书籍或章节不存在")
                    finish()
                }
                return@launch
            }

            book = b
            chapter = c

            val content = BookHelp.getContent(b, c) ?: ""

            withContext(Dispatchers.Main) {
                contentLoaded = content
                binding.editor.setText(content)
                binding.titleBar.title = c.title
                setupEditor()
                // 把当前章节索引注入 AI 面板（写作页无 ReadBook 全局态）
                aiPanel?.setChapterContext(c.index)
                observeAiChapterUpdates(bookUrl)
            }
        }
    }

    /** 观察 Agent 改写章节事件，自动刷新编辑器（免保存标记） */
    private fun observeAiChapterUpdates(bookUrl: String) {
        AiToolStatusBus.chapterUpdatedLiveData.observe(this) { updatedBookUrl ->
            if (updatedBookUrl != bookUrl) return@observe
            lifecycleScope.launch(Dispatchers.IO) {
                val b = book ?: return@launch
                val c = appDb.bookChapterDao.getChapterByUrl(bookUrl, currentChapterUrl) ?: return@launch
                val fresh = BookHelp.getContent(b, c) ?: return@launch
                withContext(Dispatchers.Main) {
                    contentLoaded = fresh
                    isModified = false
                    binding.editor.setText(fresh)
                    invalidateOptionsMenu()
                    toastOnUi("AI 已更新正文")
                }
            }
        }
    }

    private fun setupEditor() {
        binding.editor.addTextChangedListener(
            onTextChanged = { text, _, _, _ ->
                isModified = text.toString() != contentLoaded
                invalidateOptionsMenu()
            }
        )
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menu.apply {
            add(0, 1, 0, "保存").apply {
                setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            }
            add(0, 3, 1, "问 AI").apply {
                setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            }
            add(0, 2, 2, "撤销修改").apply {
                setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            }
        }
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu?): Boolean {
        menu?.findItem(1)?.isEnabled = isModified
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            1 -> saveContent()
            3 -> askAiWithSelection()
            2 -> discardChanges()
            else -> return super.onCompatOptionsItemSelected(item)
        }
        return true
    }

    private fun saveContent() {
        val b = book ?: return
        val c = chapter ?: return
        val text = binding.editor.text?.toString() ?: ""

        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                BookHelp.saveText(b, c, text)
                val wordCount = text.length
                appDb.bookChapterDao.upWordCount(c.bookUrl, c.url, wordCount.toString())
            }.onSuccess {
                withContext(Dispatchers.Main) {
                    contentLoaded = text
                    isModified = false
                    invalidateOptionsMenu()
                    toastOnUi("已保存")
                }
            }.onFailure { error ->
                withContext(Dispatchers.Main) {
                    toastOnUi("保存失败: ${error.localizedMessage ?: "未知错误"}")
                }
            }
        }
    }

    private fun discardChanges() {
        MaterialAlertDialogBuilder(this)
            .setTitle("撤销修改")
            .setMessage("确定放弃所有未保存的修改吗？")
            .setPositiveButton("确定") { _, _ ->
                binding.editor.setText(contentLoaded)
                binding.editor.setSelection(contentLoaded.length)
                isModified = false
                invalidateOptionsMenu()
                toastOnUi("已撤销")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onBackPressed() {
        if (isModified) {
            MaterialAlertDialogBuilder(this)
                .setTitle("未保存的修改")
                .setMessage("有未保存的修改，是否保存后退出？")
                .setPositiveButton("保存并退出") { _, _ ->
                    saveContent()
                    super.onBackPressed()
                }
                .setNeutralButton("不保存退出") { _, _ ->
                    super.onBackPressed()
                }
                .setNegativeButton("取消", null)
                .show()
        } else {
            super.onBackPressed()
        }
    }
}
