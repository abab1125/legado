package io.legado.app.ui.write

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import io.legado.app.base.VMBaseActivity
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookChapter
import io.legado.app.databinding.ActivityWriteBookBinding
import io.legado.app.databinding.ItemWriteChapterBinding
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class WriteBookActivity :
    VMBaseActivity<ActivityWriteBookBinding, WriteBookViewModel>() {

    override val binding by viewBinding(ActivityWriteBookBinding::inflate)
    override val viewModel by viewModels<WriteBookViewModel>()

    private var bookUrl: String = ""
    private val chapterAdapter by lazy { ChapterListAdapter() }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        bookUrl = intent.getStringExtra("bookUrl") ?: return
        viewModel.loadBook(bookUrl)

        initView()
        observeData()
        observeToast()
    }

    private fun initView() {
        binding.recyclerChapters.layoutManager = LinearLayoutManager(this)
        binding.recyclerChapters.adapter = chapterAdapter

        // 操作按钮
        binding.btnNewChapter.setOnClickListener { showNewChapterDialog() }
        binding.btnPrompts.setOnClickListener {
            PromptManageDialog.newInstance().show(supportFragmentManager, "prompt")
        }
        binding.btnKnowledge.setOnClickListener {
            KnowledgeManageDialog.newInstance(bookUrl).show(supportFragmentManager, "knowledge")
        }
        binding.btnOutline.setOnClickListener { toastOnUi("章纲浏览 - 开发中") }
    }

    private fun observeData() {
        viewModel.bookLive.observe(this) { book ->
            if (book == null) return@observe
            binding.tvBookName.text = book.name
            binding.tvBookAuthor.text = book.author
        }

        viewModel.chaptersLive
            .observe(this) { chapters ->
                chapterAdapter.setItems(chapters)
                binding.tvChapterCount.text = "${chapters.size}章"
            }
    }

    private fun observeToast() {
        viewModel.toastMsg.observe(this) { msg ->
            toastOnUi(msg)
        }
    }

    private fun showNewChapterDialog() {
        val titleInput = TextInputEditText(this).apply { hint = "章节名" }
        val outlineInput = TextInputEditText(this).apply { hint = "章纲（选填）" }

        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(titleInput)
            addView(outlineInput)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("新建章节")
            .setView(container)
            .setPositiveButton("创建") { _, _ ->
                viewModel.createChapter(
                    titleInput.text?.toString() ?: "",
                    outlineInput.text?.toString() ?: ""
                )
            }
            .setNegativeButton("取消", null)
            .show()
    }

    inner class ChapterListAdapter :
        RecyclerAdapter<BookChapter, ItemWriteChapterBinding>(this@WriteBookActivity) {

        override fun getViewBinding(parent: ViewGroup): ItemWriteChapterBinding {
            return ItemWriteChapterBinding.inflate(inflater, parent, false)
        }

        override fun registerListener(holder: ItemViewHolder, binding: ItemWriteChapterBinding) {
            binding.root.setOnClickListener {
                getItemByLayoutPosition(holder.layoutPosition)?.let { chapter ->
                    val intent = Intent(this@WriteBookActivity, ChapterEditorActivity::class.java)
                    intent.putExtra("bookUrl", chapter.bookUrl)
                    intent.putExtra("chapterUrl", chapter.url)
                    startActivity(intent)
                }
            }
            binding.ivMore.setOnClickListener { v ->
                getItemByLayoutPosition(holder.layoutPosition)?.let { chapter ->
                    showChapterMenu(v, chapter)
                }
            }
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: ItemWriteChapterBinding,
            item: BookChapter,
            payloads: MutableList<Any>
        ) {
            binding.tvChapterIndex.text = "${item.index + 1}"
            binding.tvChapterTitle.text = item.title
            val outline = item.variableMap["outline"]
            binding.tvChapterSummary.text = outline ?: ""
            binding.tvChapterSummary.visibility =
                if (outline.isNullOrBlank()) android.view.View.GONE else android.view.View.VISIBLE
        }
    }

    private fun showChapterMenu(anchor: View, chapter: BookChapter) {
        PopupMenu(this, anchor).apply {
            menu.add("编辑章纲")
            menu.add("写正文")
            menu.add("重命名")
            menu.add("删除章节")
            setOnMenuItemClickListener { item ->
                when (item.title) {
                    "编辑章纲" -> showEditOutlineDialog(chapter)
                    "写正文" -> {
                        val intent = Intent(this@WriteBookActivity, ChapterEditorActivity::class.java)
                        intent.putExtra("bookUrl", chapter.bookUrl)
                        intent.putExtra("chapterUrl", chapter.url)
                        startActivity(intent)
                    }
                    "重命名" -> showRenameChapterDialog(chapter)
                    "删除章节" -> confirmDeleteChapter(chapter)
                }
                true
            }
            show()
        }
    }

    private fun showEditOutlineDialog(chapter: BookChapter) {
        val input = TextInputEditText(this).apply {
            setText(viewModel.getChapterOutline(chapter))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("编辑章纲 - ${chapter.title}")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                viewModel.updateChapterOutline(chapter, input.text?.toString() ?: "")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showRenameChapterDialog(chapter: BookChapter) {
        val input = TextInputEditText(this).apply {
            setText(chapter.title)
            selectAll()
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("重命名章节")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val newTitle = input.text?.toString()?.trim()
                if (!newTitle.isNullOrBlank()) {
                    viewModel.updateChapterTitle(chapter, newTitle)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmDeleteChapter(chapter: BookChapter) {
        MaterialAlertDialogBuilder(this)
            .setTitle("删除章节")
            .setMessage("确定删除「${chapter.title}」吗？正文文件不会被自动删除。")
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    appDb.bookChapterDao.deleteByUrl(chapter.bookUrl, chapter.url)
                }
                toastOnUi("已删除")
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
