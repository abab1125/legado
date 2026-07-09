package io.legado.app.ui.book.bookmark

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Bookmark
import io.legado.app.databinding.ActivityAllBookmarkBinding
import io.legado.app.ui.book.thought.ObsidianExportDialog
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 所有书签
 */
import kotlinx.coroutines.flow.combine
import io.legado.app.ui.book.thought.BookThoughtDialog

class AllBookmarkActivity : VMBaseActivity<ActivityAllBookmarkBinding, AllBookmarkViewModel>(),
    BookmarkAdapter.Callback {

    override val viewModel by viewModels<AllBookmarkViewModel>()
    override val binding by viewBinding(ActivityAllBookmarkBinding::inflate)
    private val adapter by lazy {
        BookmarkAdapter(this, this)
    }
    private val exportDir = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            when (it.requestCode) {
                1 -> viewModel.exportBookmark(uri)
                2 -> viewModel.exportBookmarkMd(uri)
            }
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initView()
        lifecycleScope.launch {
            appDb.bookmarkDao.flowAll().combine(appDb.bookThoughtDao.flowAll()) { bookmarks, thoughts ->
                val list = mutableListOf<MarkItem>()
                bookmarks.forEach {
                    list.add(MarkItem(it.bookName, it.bookAuthor, it.chapterName, it.bookText, it.content, it.time, bookmark = it))
                }
                thoughts.forEach {
                    list.add(MarkItem(it.bookName, it.bookAuthor, it.chapterName, it.selectedText, it.thought, it.createTime, thought = it))
                }
                list.sortByDescending { it.time }
                list
            }.catch {
                AppLog.put("所有书签界面获取数据失败\n${it.localizedMessage}", it)
            }.flowOn(IO).collect {
                adapter.setItems(it)
            }
        }
    }

    private fun initView() {
        binding.recyclerView.addItemDecoration(BookmarkDecoration(adapter))
        binding.recyclerView.adapter = adapter
        binding.recyclerView.applyNavigationBarPadding()
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.bookmark, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_export -> exportDir.launch {
                requestCode = 1
            }

            R.id.menu_export_md -> exportDir.launch {
                requestCode = 2
            }

            R.id.menu_export_obsidian -> {
                showDialogFragment(ObsidianExportDialog.newInstance())
            }
        }
        return super.onCompatOptionsItemSelected(item)
    }

    override fun onItemClick(item: MarkItem, position: Int) {
        lifecycleScope.launch {
            val book = withContext(IO) {
                appDb.bookDao.getBook(item.bookName, item.bookAuthor)
            }
            if (book == null) {
                if (item.bookmark != null) {
                    showDialogFragment(BookmarkDialog(item.bookmark, position))
                } else if (item.thought != null) {
                    showDialogFragment(BookThoughtDialog(item.thought, position))
                }
            } else {
                startActivityForBook(book) {
                    val chapterIndex = item.bookmark?.chapterIndex ?: item.thought?.chapterIndex ?: 0
                    val chapterPos = item.bookmark?.chapterPos ?: item.thought?.chapterPos ?: 0
                    putExtra("index", chapterIndex)
                    putExtra("chapterPos", chapterPos)
                }
            }
        }
    }

    override fun onItemLongClick(item: MarkItem, position: Int): Boolean {
        if (item.bookmark != null) {
            showDialogFragment(BookmarkDialog(item.bookmark, position))
        } else if (item.thought != null) {
            showDialogFragment(BookThoughtDialog(item.thought, position))
        }
        return true
    }
}