package io.legado.app.ui.write

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.util.UUID

class WriteBookViewModel : ViewModel() {

    private var book: Book? = null

    private val _toastMsg = MutableLiveData<String>()
    val toastMsg: LiveData<String> = _toastMsg

    private val _bookLive = MutableLiveData<Book?>()
    val bookLive: LiveData<Book?> = _bookLive

    private val _chaptersLive = MutableLiveData<List<BookChapter>>()
    val chaptersLive: LiveData<List<BookChapter>> = _chaptersLive

    fun loadBook(bookUrl: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val b = appDb.bookDao.getBook(bookUrl)
            _bookLive.postValue(b)
            book = b
            loadChapters()
        }
    }

    private fun loadChapters() {
        val url = book?.bookUrl ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val chapters = appDb.bookChapterDao.getChapterList(url)
            _chaptersLive.postValue(chapters)
        }
    }

    /** 新建章节 */
    fun createChapter(title: String, outline: String = "") {
        val b = book ?: return
        if (title.isBlank()) {
            _toastMsg.postValue("章节名不能为空")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val existing = appDb.bookChapterDao.getChapterList(b.bookUrl)
            val newIndex = (existing.maxOfOrNull { it.index } ?: -1) + 1
            val chapterUrl = "write_${b.bookUrl}_$newIndex"

            val chapter = BookChapter(
                url = chapterUrl,
                title = title.trim(),
                bookUrl = b.bookUrl,
                index = newIndex,
                baseUrl = chapterUrl
            )
            if (outline.isNotBlank()) {
                chapter.putVariable("outline", outline.trim())
            }
            appDb.bookChapterDao.insert(chapter)

            b.totalChapterNum = newIndex + 1
            b.durChapterTitle = title.trim()
            b.durChapterIndex = newIndex
            appDb.bookDao.update(b)
            book = b
            _bookLive.postValue(b)
            loadChapters()
            _toastMsg.postValue("章节「${title.trim()}」已创建")
        }
    }

    /** 删除单个章节 */
    fun deleteChapter(chapter: BookChapter) {
        viewModelScope.launch(Dispatchers.IO) {
            appDb.bookChapterDao.deleteByUrl(chapter.bookUrl, chapter.url)
            loadChapters()
            _toastMsg.postValue("已删除章节「${chapter.title}」")
        }
    }

    /** 更新章节标题 */
    fun updateChapterTitle(chapter: BookChapter, newTitle: String) {
        viewModelScope.launch(Dispatchers.IO) {
            chapter.title = newTitle.trim()
            appDb.bookChapterDao.update(chapter)
            loadChapters()
            _toastMsg.postValue("已更新")
        }
    }

    /** 更新章纲 */
    fun updateChapterOutline(chapter: BookChapter, outline: String) {
        viewModelScope.launch(Dispatchers.IO) {
            chapter.putVariable("outline", outline.trim())
            appDb.bookChapterDao.update(chapter)
            loadChapters()
            _toastMsg.postValue("章纲已保存")
        }
    }

    fun getChapterOutline(chapter: BookChapter): String {
        return chapter.variableMap["outline"] ?: ""
    }
}
