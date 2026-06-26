package io.legado.app.ui.write

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.databinding.ActivityWriteDeskBinding
import io.legado.app.databinding.ItemWriteBookBinding
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.utils.applyTint
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.ui.write.PromptManageDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WriteDeskActivity :
    VMBaseActivity<ActivityWriteDeskBinding, WriteDeskViewModel>() {

    override val binding by viewBinding(ActivityWriteDeskBinding::inflate)
    override val viewModel by viewModels<WriteDeskViewModel>()

    private val adapter by lazy { BookListAdapter() }

    private var bookStatsMap = mapOf<String, WriteDeskViewModel.BookStats>()

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initView()
        observeData()
        observeToast()
    }

    private fun initView() {
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        binding.fabAddBook.setOnClickListener {
            showNewBookDialog()
        }

        binding.titleBar.findViewById<View>(R.id.search_view)?.let { search ->
            search.applyTint(primaryTextColor)
        }

        binding.titleBar.menu.add("提示词库").apply {
            setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        binding.titleBar.toolbar.setOnMenuItemClickListener { item ->
            when (item.title.toString()) {
                "提示词库" -> {
                    PromptManageDialog.newInstance().show(supportFragmentManager, "prompt")
                    true
                }
                else -> false
            }
        }
    }

    private fun observeData() {
        lifecycleScope.launch {
            viewModel.booksFlow
                .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
                .collect { books ->
                    loadStats(books)
                }
        }
    }

    private fun loadStats(books: List<Book>) {
        lifecycleScope.launch(Dispatchers.IO) {
            val statsMap = books.associate { book ->
                book.bookUrl to viewModel.getBookStats(book.bookUrl)
            }
            withContext(Dispatchers.Main) {
                bookStatsMap = statsMap
                adapter.setItems(books)
            }
        }
    }

    private fun observeToast() {
        viewModel.toastMsg.observe(this) { msg ->
            toastOnUi(msg)
        }
    }

    private fun showNewBookDialog() {
        val nameInput = TextInputEditText(this).apply {
            hint = "作品名称"
        }
        val authorInput = TextInputEditText(this).apply {
            hint = "作者（选填）"
        }

        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(nameInput)
            addView(authorInput)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("新建作品")
            .setView(container)
            .setPositiveButton("创建") { _, _ ->
                viewModel.createNewBook(
                    nameInput.text?.toString() ?: "",
                    authorInput.text?.toString() ?: ""
                )
            }
            .setNegativeButton("取消", null)
            .show()
    }

    inner class BookListAdapter :
        RecyclerAdapter<Book, ItemWriteBookBinding>(this@WriteDeskActivity) {

        override fun getViewBinding(parent: ViewGroup): ItemWriteBookBinding {
            return ItemWriteBookBinding.inflate(inflater, parent, false)
        }

        override fun registerListener(holder: ItemViewHolder, binding: ItemWriteBookBinding) {
            binding.root.setOnClickListener {
                getItemByLayoutPosition(holder.layoutPosition)?.let { book ->
                    val intent = Intent(this@WriteDeskActivity, WriteBookActivity::class.java)
                    intent.putExtra("bookUrl", book.bookUrl)
                    startActivity(intent)
                }
            }
            binding.ivMore.setOnClickListener { v ->
                getItemByLayoutPosition(holder.layoutPosition)?.let { book ->
                    showBookMenu(v, book)
                }
            }
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: ItemWriteBookBinding,
            item: Book,
            payloads: MutableList<Any>
        ) {
            binding.tvBookName.text = item.name
            binding.tvBookAuthor.text = item.author

            val stats = bookStatsMap[item.bookUrl]
            val parts = mutableListOf<String>()
            stats?.let {
                parts.add("${it.chapterCount}章")
                if (it.promptCount > 0) parts.add("${it.promptCount}提示词")
                if (it.knowledgeCount > 0) parts.add("${it.knowledgeCount}知识点")
            }
            binding.tvBookStats.text = parts.joinToString(" · ").ifEmpty { "暂无数据" }
        }
    }

    private fun showBookMenu(anchor: View, book: Book) {
        PopupMenu(this, anchor).apply {
            menu.add("编辑")
            menu.add("删除")
            setOnMenuItemClickListener { item ->
                when (item.title) {
                    "删除" -> confirmDeleteBook(book)
                    "编辑" -> showEditBookDialog(book)
                }
                true
            }
            show()
        }
    }

    private fun confirmDeleteBook(book: Book) {
        MaterialAlertDialogBuilder(this)
            .setTitle("删除作品")
            .setMessage("确定删除「${book.name}」吗？关联的目录、正文、提示词、知识点将一并删除。")
            .setPositiveButton("删除") { _, _ ->
                viewModel.deleteBook(book)
                toastOnUi("已删除「${book.name}」")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showEditBookDialog(book: Book) {
        val nameInput = TextInputEditText(this).apply {
            setText(book.name)
            selectAll()
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("修改作品名")
            .setView(nameInput)
            .setPositiveButton("保存") { _, _ ->
                val newName = nameInput.text?.toString()?.trim()
                if (!newName.isNullOrBlank()) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        appDb.bookDao.update(book.copy(name = newName.trim()))
                    }
                    toastOnUi("已更新")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
