package io.legado.app.ui.write

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.KnowledgePoint
import io.legado.app.data.entities.WritingPrompt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.util.UUID

class WriteDeskViewModel : ViewModel() {

    /** 所有书籍列表（Flow 自动更新） */
    val booksFlow: Flow<List<Book>> = appDb.bookDao.flowAll()

    private val _toastMsg = MutableLiveData<String>()
    val toastMsg: LiveData<String> = _toastMsg

    /**
     * 创建一本新作品（本地书）
     * 使用独立 bookUrl（UUID）避免冲突
     */
    fun createNewBook(name: String, author: String) {
        if (name.isBlank()) {
            _toastMsg.postValue("书名不能为空")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val bookUrl = "write_${UUID.randomUUID()}"
            val book = Book(
                bookUrl = bookUrl,
                name = name.trim(),
                author = author.trim().ifBlank { "佚名" },
                origin = BookType.localTag,
                type = BookType.text or BookType.local,
                durChapterTime = System.currentTimeMillis(),
                latestChapterTime = System.currentTimeMillis(),
                totalChapterNum = 0,
                group = 0,
                order = 0
            )
            appDb.bookDao.insert(book)
            _toastMsg.postValue("新作品「${name}」已创建")
        }
    }

    /**
     * 删除作品及其所有关联数据
     */
    fun deleteBook(book: Book) {
        viewModelScope.launch(Dispatchers.IO) {
            appDb.bookDao.delete(book)
            appDb.bookChapterDao.delByBook(book.bookUrl)
            appDb.knowledgePointDao.deleteByBook(book.bookUrl)
        }
    }

    /**
     * 获取一本书的统计数据
     */
    data class BookStats(
        val chapterCount: Int = 0,
        val promptCount: Int = 0,
        val knowledgeCount: Int = 0
    )

    suspend fun getBookStats(bookUrl: String): BookStats {
        val chapters = appDb.bookChapterDao.getChapterList(bookUrl, 0, Int.MAX_VALUE)
        val knowledge = appDb.knowledgePointDao.countByBook(bookUrl)
        return BookStats(
            chapterCount = chapters.size,
            promptCount = appDb.writingPromptDao.count(),
            knowledgeCount = knowledge
        )
    }
}
