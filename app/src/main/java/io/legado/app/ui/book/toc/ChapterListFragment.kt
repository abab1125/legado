package io.legado.app.ui.book.toc

import android.annotation.SuppressLint
import android.app.Activity.RESULT_OK
import android.content.Intent
import android.graphics.PorterDuff
import android.os.Bundle
import android.view.View
import android.widget.PopupMenu
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.VMBaseFragment
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.databinding.FragmentChapterListBinding
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isVideo
import io.legado.app.help.book.simulatedTotalChapterNum
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.getPrimaryTextColor
import io.legado.app.ui.widget.recycler.UpLinearLayoutManager
import io.legado.app.ui.widget.recycler.VerticalDivider
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.observeEvent
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.Default
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChapterListFragment : VMBaseFragment<TocViewModel>(R.layout.fragment_chapter_list),
    ChapterListAdapter.Callback,
    TocViewModel.ChapterListCallBack {
    override val viewModel by activityViewModels<TocViewModel>()
    private val binding by viewBinding(FragmentChapterListBinding::bind)
    private val mLayoutManager by lazy { UpLinearLayoutManager(requireContext()) }
    private val adapter by lazy { ChapterListAdapter(requireContext(), this) }
    private var durChapterIndex = 0

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) = binding.run {
        viewModel.chapterListCallBack = this@ChapterListFragment
        val bbg = bottomBackground
        val btc = requireContext().getPrimaryTextColor(ColorUtils.isColorLight(bbg))
        llChapterBaseInfo.setBackgroundColor(bbg)
        tvCurrentChapterInfo.setTextColor(btc)
        ivChapterTop.setColorFilter(btc, PorterDuff.Mode.SRC_IN)
        ivChapterBottom.setColorFilter(btc, PorterDuff.Mode.SRC_IN)
        initRecyclerView()
        initView()
        viewModel.bookData.observe(this@ChapterListFragment) {
            initBook(it)
        }
    }

    private fun initRecyclerView() {
        binding.recyclerView.layoutManager = mLayoutManager
        binding.recyclerView.addItemDecoration(VerticalDivider(requireContext()))
        binding.recyclerView.adapter = adapter
    }

    private fun initView() = binding.run {
        ivChapterTop.setOnClickListener {
            mLayoutManager.scrollToPositionWithOffset(0, 0)
        }
        ivChapterBottom.setOnClickListener {
            if (adapter.itemCount > 0) {
                mLayoutManager.scrollToPositionWithOffset(adapter.itemCount - 1, 0)
            }
        }
        tvCurrentChapterInfo.setOnClickListener {
            mLayoutManager.scrollToPositionWithOffset(durChapterIndex, 0)
        }
        binding.llChapterBaseInfo.applyNavigationBarPadding()
    }

    @SuppressLint("SetTextI18n")
    private fun initBook(book: Book) {
        lifecycleScope.launch {
            upChapterList(null)
            durChapterIndex = book.durChapterIndex
            binding.tvCurrentChapterInfo.text =
                "${book.durChapterTitle}(${book.durChapterIndex + 1}/${book.simulatedTotalChapterNum()})"
            initCacheFileNames(book)
        }
    }

    private fun initCacheFileNames(book: Book) {
        lifecycleScope.launch(IO) {
            adapter.cacheFileNames.addAll(BookHelp.getChapterFiles(book))
            withContext(Main) {
                adapter.notifyItemRangeChanged(0, adapter.itemCount, true)
            }
        }
    }

    override fun observeLiveBus() {
        observeEvent<Pair<Book, BookChapter>>(EventBus.SAVE_CONTENT) { (book, chapter) ->
            viewModel.bookData.value?.bookUrl?.let { bookUrl ->
                if (book.bookUrl == bookUrl) {
                    adapter.cacheFileNames.add(chapter.getFileName())
                    if (viewModel.searchKey.isNullOrEmpty()) {
                        adapter.notifyItemChanged(chapter.index, true)
                    } else {
                        adapter.getItems().forEachIndexed { index, bookChapter ->
                            if (bookChapter.index == chapter.index) {
                                adapter.notifyItemChanged(index, true)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun upChapterList(searchKey: String?) {
        lifecycleScope.launch {
            withContext(IO) {
                val end = (book?.simulatedTotalChapterNum() ?: Int.MAX_VALUE) - 1
                when {
                    searchKey.isNullOrBlank() ->
                        appDb.bookChapterDao.getChapterList(viewModel.bookUrl, 0, end).also {
                            chapterList = it
                        }

                    else -> appDb.bookChapterDao.search(viewModel.bookUrl, searchKey, 0, end)
                }
            }.let {
                adapter.setItems(it)
            }
        }
    }

    override fun onListChanged() {
        lifecycleScope.launch {
            var scrollPos = 0
            withContext(Default) {
                adapter.getItems().forEachIndexed { index, bookChapter ->
                    if (bookChapter.index >= durChapterIndex) {
                        return@withContext
                    }
                    scrollPos = index
                }
            }
            mLayoutManager.scrollToPositionWithOffset(scrollPos, 0)
            adapter.upDisplayTitles(scrollPos)
        }
    }

    override fun clearDisplayTitle() {
        adapter.clearDisplayTitle()
        adapter.upDisplayTitles(mLayoutManager.findFirstVisibleItemPosition())
    }

    override fun upAdapter() {
        adapter.notifyItemRangeChanged(0, adapter.itemCount)
    }

    override val scope: CoroutineScope
        get() = lifecycleScope

    override val book: Book?
        get() = viewModel.bookData.value

    override val isLocalBook: Boolean
        get() = viewModel.bookData.value?.isLocal == true

    override fun durChapterIndex(): Int {
        return durChapterIndex
    }
    private var chapterList: List<BookChapter>? = null

    override fun openChapter(bookChapter: BookChapter) {
        activity?.run {
            if (book?.isVideo == true) {
                val volumes = arrayListOf<BookChapter>()
                chapterList?.forEach { chapter ->
                    if (chapter.isVolume) {
                        volumes.add(chapter)
                    }
                }
                var chapterInVolumeIndex = 0
                var durVolumeIndex = 0
                if (volumes.isNotEmpty()) {
                    for ((index, volume) in volumes.reversed().withIndex()) {
                        val first = bookChapter.index
                        if (volume.index < first) {
                            chapterInVolumeIndex = first - volume.index - 1
                            durVolumeIndex = volumes.size - index - 1
                            break
                        } else if (volume.index == first) {
                            chapterInVolumeIndex = 0
                            durVolumeIndex = volumes.size - index - 1
                            break
                        }
                    }
                } else {
                    chapterInVolumeIndex = bookChapter.index
                }
                setResult(
                    RESULT_OK, Intent()
                        .putExtra("index", bookChapter.index)
                        .putExtra("chapterChanged", bookChapter.index != durChapterIndex)
                        .putExtra("durVolumeIndex", durVolumeIndex)
                        .putExtra("chapterInVolumeIndex", chapterInVolumeIndex)
                )
                finish()
                return@run
            }
            setResult(
                RESULT_OK, Intent()
                    .putExtra("index", bookChapter.index)
                    .putExtra("chapterChanged", bookChapter.index != durChapterIndex)
            )
            finish()
        }
    }

    override fun openSummary(bookChapter: BookChapter) {
        val book = viewModel.bookData.value ?: return
        val dialog = ChapterSummaryDialog.newInstance(book, bookChapter)
        dialog.setOnDismissListener {
            // 刷新该章节行，更新章纲图标状态
            adapter.getItems().forEachIndexed { index, item ->
                if (item.url == bookChapter.url) {
                    adapter.notifyItemChanged(index)
                    return@forEachIndexed
                }
            }
        }
        dialog.show(parentFragmentManager, "chapter_summary")
    }

    override fun openChapterMenu(bookChapter: BookChapter, anchor: View) {
        val popupMenu = PopupMenu(requireContext(), anchor)
        popupMenu.menuInflater.inflate(R.menu.menu_chapter_context, popupMenu.menu)

        popupMenu.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.insert_next_chapter -> {
                    insertNextChapter(bookChapter)
                    true
                }
                R.id.auto_name_chapter -> {
                    autoNameChapter(bookChapter)
                    true
                }
                R.id.manual_name_chapter -> {
                    manualNameChapter(bookChapter)
                    true
                }
                R.id.batch_name_chapters -> {
                    batchNameChapters(bookChapter)
                    true
                }
                R.id.organize_chapters -> {
                    organizeChapters()
                    true
                }
                R.id.move_chapter_up -> {
                    moveChapterUp(bookChapter)
                    true
                }
                R.id.move_chapter_down -> {
                    moveChapterDown(bookChapter)
                    true
                }
                R.id.delete_chapter -> {
                    deleteChapter(bookChapter)
                    true
                }
                else -> false
            }
        }
        popupMenu.show()
    }

    private fun insertNextChapter(chapter: BookChapter) {
        lifecycleScope.launch {
            val bookUrl = book?.bookUrl ?: return@launch
            val baseUrl = chapter.baseUrl
            withContext(IO) {
                // 1) 先把后面章节 index+1，腾出位置
                appDb.bookChapterDao.getChapterList(bookUrl)
                    .filter { it.index > chapter.index }
                    .forEach { appDb.bookChapterDao.update(it.copy(index = it.index + 1)) }
                // 2) 再插入新章节
                appDb.bookChapterDao.insert(BookChapter(
                    url = chapter.url + "_new_" + System.currentTimeMillis(),
                    title = "新章节",
                    bookUrl = bookUrl,
                    index = chapter.index + 1,
                    baseUrl = baseUrl
                ))
            }
            viewModel.upChapterListAdapter()
        }
    }

    private fun autoNameChapter(chapter: BookChapter) {
        // TODO: AI 自动起名，暂时用默认名
        lifecycleScope.launch {
            val newName = "第${chapter.index + 1}章 新章节"
            appDb.bookChapterDao.update(chapter.copy(title = newName))
            viewModel.upChapterListAdapter()
        }
    }

    private fun manualNameChapter(chapter: BookChapter) {
        // TODO: 弹出输入框
        lifecycleScope.launch {
            val newName = "第${chapter.index + 1}章 新章节"
            appDb.bookChapterDao.update(chapter.copy(title = newName))
            viewModel.upChapterListAdapter()
        }
    }

    private fun batchNameChapters(chapter: BookChapter) {
        // TODO: 批量起名
        lifecycleScope.launch {
            viewModel.upChapterListAdapter()
        }
    }

    private fun organizeChapters() {
        lifecycleScope.launch {
            val book = viewModel.bookData.value ?: return@launch
            val chapters = appDb.bookChapterDao.getChapterList(book.bookUrl)
            chapters.forEachIndexed { index, ch ->
                if (ch.index != index) {
                    appDb.bookChapterDao.update(ch.copy(index = index))
                }
            }
            adapter.notifyDataSetChanged()
        }
    }

    private fun moveChapterUp(chapter: BookChapter) {
        if (chapter.index <= 0) return
        lifecycleScope.launch {
            val bookUrl = book?.bookUrl ?: return@launch
            withContext(IO) {
                val chapters = appDb.bookChapterDao.getChapterList(bookUrl)
                val prevChapter = chapters.find { it.index == chapter.index - 1 } ?: return@withContext
                appDb.bookChapterDao.update(chapter.copy(index = chapter.index - 1))
                appDb.bookChapterDao.update(prevChapter.copy(index = prevChapter.index + 1))
            }
            viewModel.upChapterListAdapter()
        }
    }

    private fun moveChapterDown(chapter: BookChapter) {
        lifecycleScope.launch {
            val bookUrl = book?.bookUrl ?: return@launch
            withContext(IO) {
                val chapters = appDb.bookChapterDao.getChapterList(bookUrl)
                val nextChapter = chapters.find { it.index == chapter.index + 1 } ?: return@withContext
                appDb.bookChapterDao.update(chapter.copy(index = chapter.index + 1))
                appDb.bookChapterDao.update(nextChapter.copy(index = nextChapter.index - 1))
            }
            viewModel.upChapterListAdapter()
        }
    }

    private fun deleteChapter(chapter: BookChapter) {
        lifecycleScope.launch {
            val bookUrl = book?.bookUrl ?: return@launch
            withContext(IO) {
                appDb.bookChapterDao.deleteByUrl(bookUrl, chapter.url)
                appDb.bookChapterDao.getChapterList(bookUrl).forEachIndexed { index, ch ->
                    if (ch.index != index) {
                        appDb.bookChapterDao.update(ch.copy(index = index))
                    }
                }
            }
            viewModel.upChapterListAdapter()
        }
    }
}
