package io.legado.app.ui.write

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.legado.app.base.VMBaseActivity
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.databinding.ActivityChapterEditorBinding
import io.legado.app.help.book.BookHelp
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
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

        loadData(bookUrl, chapterUrl)
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
            add(0, 2, 1, "撤销修改").apply {
                setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            }
        }
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu?): Boolean {
        menu?.findItem(1)?.isEnabled = isModified
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            1 -> saveContent()
            2 -> discardChanges()
            else -> return super.onOptionsItemSelected(item)
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
