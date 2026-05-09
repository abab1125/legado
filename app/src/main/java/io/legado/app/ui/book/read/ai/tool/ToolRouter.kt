package io.legado.app.ui.book.read.ai.tool

import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.RssSource
import io.legado.app.help.book.isLocal
import io.legado.app.help.source.SourceHelp
import io.legado.app.model.SourceCallBack
import io.legado.app.model.localBook.LocalBook
import io.legado.app.utils.GSON
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar

sealed class ToolExecuteResult {
    data class Data(val json: String) : ToolExecuteResult()

    /**
     * 需要逐个确认的操作（用于删除等高风险操作）
     */
    data class NeedConfirmation(
        val description: String,
        val action: suspend () -> String
    ) : ToolExecuteResult()

    /**
     * 可批量确认的操作（用于分组整理、启用/禁用等低风险批量操作）
     * 同一轮 AI tool calls 中的多个此类操作会被收集后一次性展示给用户确认
     */
    data class BatchConfirmation(
        val description: String,
        val action: suspend () -> String
    ) : ToolExecuteResult()
}

object ToolRouter {

    private const val MAX_BOOKS = 100
    private const val MAX_SOURCES = 100
    private const val MAX_CHAPTERS = 200
    private const val MAX_TOP_BOOKS = 10

    suspend fun execute(name: String, args: Map<*, *>): ToolExecuteResult {
        return withContext(Dispatchers.IO) {
            try {
                when (name) {
                    // 只读工具
                    "get_bookshelf" -> ToolExecuteResult.Data(getBookshelf(args))
                    "search_bookshelf" -> ToolExecuteResult.Data(searchBookshelf(args))
                    "get_book_sources" -> ToolExecuteResult.Data(getBookSources(args))
                    "get_rss_sources" -> ToolExecuteResult.Data(getRssSources(args))
                    "get_reading_stats" -> ToolExecuteResult.Data(getReadingStats())
                    "get_book_chapters" -> ToolExecuteResult.Data(getBookChapters(args))
                    "get_book_groups" -> ToolExecuteResult.Data(getBookGroups())
                    "get_source_groups" -> ToolExecuteResult.Data(getSourceGroups())
                    // 写操作：可批量确认（分组整理、启用/禁用、创建分组）
                    "update_book_group" -> batchUpdateBookGroup(args)
                    "enable_book_source" -> batchEnableBookSource(args)
                    "enable_rss_source" -> batchEnableRssSource(args)
                    "update_book_source_group" -> batchUpdateBookSourceGroup(args)
                    "create_book_group" -> batchCreateBookGroup(args)
                    // 写操作：逐个确认（删除，不可撤销）
                    "delete_book_source" -> confirmDeleteBookSource(args)
                    "delete_rss_source" -> confirmDeleteRssSource(args)
                    "delete_book" -> confirmDeleteBook(args)
                    else -> ToolExecuteResult.Data("""{"error":"未知工具: $name"}""")
                }
            } catch (e: Exception) {
                ToolExecuteResult.Data("""{"error":"${e.message?.replace("\"", "'") ?: "未知错误"}"}""")
            }
        }
    }

    // ========== 只读工具 ==========

    private fun getBookshelf(args: Map<*, *>): String {
        val group = args["group"] as? String
        val books = if (group.isNullOrBlank()) {
            appDb.bookDao.all
        } else {
            val bookGroup = appDb.bookGroupDao.getByName(group)
            if (bookGroup != null) {
                appDb.bookDao.getBooksByGroup(bookGroup.groupId)
            } else {
                return """{"error":"未找到分组: $group"}"""
            }
        }
        val result = books.take(MAX_BOOKS).map { bookToSimple(it) }
        return GSON.toJson(result)
    }

    private fun searchBookshelf(args: Map<*, *>): String {
        val keyword = args["keyword"] as? String
        if (keyword.isNullOrBlank()) {
            return """{"error":"keyword 参数不能为空"}"""
        }
        val books = appDb.bookDao.all.filter {
            it.name.contains(keyword, ignoreCase = true) ||
                    it.author.contains(keyword, ignoreCase = true)
        }
        val result = books.take(MAX_BOOKS).map { bookToSimple(it) }
        return GSON.toJson(result)
    }

    private fun getBookSources(args: Map<*, *>): String {
        val enabled = args["enabled"] as? Boolean
        val group = args["group"] as? String
        val offset = (args["offset"] as? Double)?.toInt() ?: 0
        val allSources = appDb.bookSourceDao.allPart
        val sources = when (enabled) {
            true -> allSources.filter { it.enabled }
            false -> allSources.filter { !it.enabled }
            else -> allSources
        }
        val filtered = if (!group.isNullOrBlank()) {
            sources.filter { it.bookSourceGroup?.contains(group) == true }
        } else {
            sources
        }
        val page = filtered.drop(offset).take(MAX_SOURCES)
        val total = filtered.size
        val result = mapOf(
            "total" to total,
            "offset" to offset,
            "count" to page.size,
            "hasMore" to (offset + page.size < total),
            "sources" to page.map { source ->
                mapOf(
                    "name" to source.bookSourceName,
                    "url" to source.bookSourceUrl,
                    "enabled" to source.enabled,
                    "group" to (source.bookSourceGroup ?: ""),
                    "weight" to source.weight
                )
            }
        )
        return GSON.toJson(result)
    }

    private fun getRssSources(args: Map<*, *>): String {
        val enabled = args["enabled"] as? Boolean
        val group = args["group"] as? String
        val offset = (args["offset"] as? Double)?.toInt() ?: 0
        val sources = when {
            enabled == true -> appDb.rssSourceDao.all.filter { it.enabled }
            enabled == false -> appDb.rssSourceDao.all.filter { !it.enabled }
            else -> appDb.rssSourceDao.all
        }
        val filtered = if (!group.isNullOrBlank()) {
            sources.filter { it.sourceGroup?.contains(group) == true }
        } else {
            sources
        }
        val page = filtered.drop(offset).take(MAX_SOURCES)
        val total = filtered.size
        val result = mapOf(
            "total" to total,
            "offset" to offset,
            "count" to page.size,
            "hasMore" to (offset + page.size < total),
            "sources" to page.map { source: RssSource ->
                mapOf(
                    "name" to source.sourceName,
                    "url" to source.sourceUrl,
                    "enabled" to source.enabled,
                    "group" to (source.sourceGroup ?: "")
                )
            }
        )
        return GSON.toJson(result)
    }

    private fun getReadingStats(): String {
        val totalBooks = appDb.bookDao.allBookCount
        val totalReadTime = appDb.readRecordDao.allTime
        val allRecords = appDb.readRecordDao.allShow
        val todayStart = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val todayRecords = allRecords.filter { it.lastRead >= todayStart }
        val todayReadTime = todayRecords.sumOf { it.readTime }
        val readDays = allRecords.map {
            Calendar.getInstance().apply { timeInMillis = it.lastRead }.get(Calendar.DAY_OF_YEAR)
        }.distinct().size
        val topBooks = allRecords.sortedByDescending { it.readTime }
            .take(MAX_TOP_BOOKS).map { mapOf("name" to it.bookName, "readTime" to it.readTime) }
        return GSON.toJson(mapOf(
            "totalBooks" to totalBooks,
            "totalReadTimeMinutes" to (totalReadTime / 60000),
            "todayReadTimeMinutes" to (todayReadTime / 60000),
            "readDays" to readDays,
            "topBooks" to topBooks
        ))
    }

    private fun getBookChapters(args: Map<*, *>): String {
        val bookName = args["book_name"] as? String
        if (bookName.isNullOrBlank()) return """{"error":"book_name 参数不能为空"}"""
        val book = appDb.bookDao.all.find { it.name == bookName }
            ?: return """{"error":"未找到书籍: $bookName"}"""
        val chapters = appDb.bookChapterDao.getChapterList(book.bookUrl)
        return GSON.toJson(chapters.take(MAX_CHAPTERS).map { ch ->
            mapOf("index" to (ch.index + 1), "title" to ch.title)
        })
    }

    private fun getBookGroups(): String {
        return GSON.toJson(appDb.bookGroupDao.all.map { g ->
            mapOf("id" to g.groupId, "name" to g.groupName)
        })
    }

    private fun getSourceGroups(): String {
        return GSON.toJson(appDb.bookSourceDao.allGroups())
    }

    // ========== 写操作：BatchConfirmation（可批量确认，低风险） ==========

    private suspend fun batchUpdateBookGroup(args: Map<*, *>): ToolExecuteResult {
        val bookName = args["book_name"] as? String
        val groupName = args["group_name"] as? String
        if (bookName.isNullOrBlank() || groupName.isNullOrBlank()) {
            return ToolExecuteResult.Data("""{"error":"book_name 和 group_name 参数不能为空"}""")
        }
        val book = appDb.bookDao.all.find { it.name == bookName }
            ?: return ToolExecuteResult.Data("""{"error":"未找到书籍: $bookName"}""")
        val group = appDb.bookGroupDao.getByName(groupName)
            ?: return ToolExecuteResult.Data("""{"error":"未找到分组: $groupName"}""")
        return ToolExecuteResult.BatchConfirmation(
            description = "将《${book.name}》移入分组「${group.groupName}」"
        ) {
            withContext(Dispatchers.IO) {
                book.group = group.groupId
                book.save()
            }
            """{"success":true,"message":"已将《${book.name}》移入分组「${group.groupName}」"}"""
        }
    }

    private suspend fun batchEnableBookSource(args: Map<*, *>): ToolExecuteResult {
        val sourceName = args["source_name"] as? String
        val enabled = args["enabled"] as? Boolean
        if (sourceName.isNullOrBlank() || enabled == null) {
            return ToolExecuteResult.Data("""{"error":"source_name 和 enabled 参数不能为空"}""")
        }
        val source = appDb.bookSourceDao.allPart.find { it.bookSourceName == sourceName }
            ?: return ToolExecuteResult.Data("""{"error":"未找到书源: $sourceName"}""")
        val action = if (enabled) "启用" else "禁用"
        return ToolExecuteResult.BatchConfirmation(
            description = "${action}书源「${source.bookSourceName}」"
        ) {
            withContext(Dispatchers.IO) {
                appDb.bookSourceDao.enable(source.bookSourceUrl, enabled)
            }
            """{"success":true,"message":"已${action}书源「${source.bookSourceName}」"}"""
        }
    }

    private suspend fun batchEnableRssSource(args: Map<*, *>): ToolExecuteResult {
        val sourceName = args["source_name"] as? String
        val enabled = args["enabled"] as? Boolean
        if (sourceName.isNullOrBlank() || enabled == null) {
            return ToolExecuteResult.Data("""{"error":"source_name 和 enabled 参数不能为空"}""")
        }
        val source = appDb.rssSourceDao.all.find { it.sourceName == sourceName }
            ?: return ToolExecuteResult.Data("""{"error":"未找到订阅源: $sourceName"}""")
        val action = if (enabled) "启用" else "禁用"
        return ToolExecuteResult.BatchConfirmation(
            description = "${action}订阅源「${source.sourceName}」"
        ) {
            withContext(Dispatchers.IO) {
                source.enabled = enabled
                appDb.rssSourceDao.update(source)
            }
            """{"success":true,"message":"已${action}订阅源「${source.sourceName}」"}"""
        }
    }

    private suspend fun batchUpdateBookSourceGroup(args: Map<*, *>): ToolExecuteResult {
        val sourceName = args["source_name"] as? String
        val groupName = args["group_name"] as? String
        if (sourceName.isNullOrBlank() || groupName.isNullOrBlank()) {
            return ToolExecuteResult.Data("""{ "error": "source_name 和 group_name 参数不能为空"}""".trimIndent())
        }
        val source = appDb.bookSourceDao.allPart.find { it.bookSourceName == sourceName }
            ?: return ToolExecuteResult.Data("""{ "error": "未找到书源: $sourceName"}""".trimIndent())
        val fullSource = appDb.bookSourceDao.getBookSource(source.bookSourceUrl)
            ?: return ToolExecuteResult.Data("""{ "error": "获取书源详情失败"}""".trimIndent())
        val oldGroup = fullSource.bookSourceGroup ?: ""
        return ToolExecuteResult.BatchConfirmation(
            description = "将书源「${source.bookSourceName}」从分组「$oldGroup」移入分组「${groupName}」"
        ) {
            withContext(Dispatchers.IO) {
                // 直接替换分组（而非追加），确保操作生效
                fullSource.bookSourceGroup = groupName
                appDb.bookSourceDao.update(fullSource)
            }
            """{"success":true,"message":"已将书源「${source.bookSourceName}」移入分组「${groupName}」"}"""
        }
    }

    private suspend fun batchCreateBookGroup(args: Map<*, *>): ToolExecuteResult {
        val groupName = args["group_name"] as? String
        if (groupName.isNullOrBlank()) {
            return ToolExecuteResult.Data("""{"error":"group_name 参数不能为空"}""")
        }
        if (!appDb.bookGroupDao.canAddGroup) {
            return ToolExecuteResult.Data("""{"error":"分组数量已达上限（最多64个），无法创建新分组"}""")
        }
        if (appDb.bookGroupDao.getByName(groupName) != null) {
            return ToolExecuteResult.Data("""{"error":"分组「${groupName}」已存在"}""")
        }
        val maxOrder = appDb.bookGroupDao.maxOrder
        return ToolExecuteResult.BatchConfirmation(
            description = "创建书籍分组「${groupName}」"
        ) {
            withContext(Dispatchers.IO) {
                val newId = appDb.bookGroupDao.getUnusedId()
                val group = BookGroup(
                    groupId = newId,
                    groupName = groupName,
                    order = maxOrder + 1,
                    show = true
                )
                appDb.bookGroupDao.insert(group)
            }
            """{"success":true,"message":"已创建分组「${groupName}」"}"""
        }
    }

    // ========== 写操作：NeedConfirmation（逐个确认，删除等高风险） ==========

    private suspend fun confirmDeleteBook(args: Map<*, *>): ToolExecuteResult {
        val bookName = args["book_name"] as? String
        if (bookName.isNullOrBlank()) {
            return ToolExecuteResult.Data("""{ "error": "book_name 参数不能为空"}""".trimIndent())
        }
        val book = appDb.bookDao.all.find { it.name == bookName }
            ?: return ToolExecuteResult.Data("""{ "error": "未找到书籍: $bookName"}""".trimIndent())
        return ToolExecuteResult.NeedConfirmation(
            description = "从书架移除《${book.name}》（不会删除本地文件，但此操作不可撤销）"
        ) {
            withContext(Dispatchers.IO) {
                appDb.bookDao.delete(book)
                if (book.isLocal) {
                    LocalBook.deleteBook(book, false)
                } else {
                    val source = appDb.bookSourceDao.getBookSource(book.origin)
                    SourceCallBack.callBackBook(SourceCallBack.DEL_BOOK_SHELF, source, book)
                }
            }
            """{ "success": true, "message": "已从书架移除《${book.name}》"}""".trimIndent()
        }
    }

    private suspend fun confirmDeleteBookSource(args: Map<*, *>): ToolExecuteResult {
        val sourceName = args["source_name"] as? String
        if (sourceName.isNullOrBlank()) {
            return ToolExecuteResult.Data("""{"error":"source_name 参数不能为空"}""")
        }
        val source = appDb.bookSourceDao.allPart.find { it.bookSourceName == sourceName }
            ?: return ToolExecuteResult.Data("""{"error":"未找到书源: $sourceName"}""")
        return ToolExecuteResult.NeedConfirmation(
            description = "删除书源「${source.bookSourceName}」（此操作不可撤销）"
        ) {
            withContext(Dispatchers.IO) {
                SourceHelp.deleteBookSource(source.bookSourceUrl)
            }
            """{"success":true,"message":"已删除书源「${source.bookSourceName}」"}"""
        }
    }

    private suspend fun confirmDeleteRssSource(args: Map<*, *>): ToolExecuteResult {
        val sourceName = args["source_name"] as? String
        if (sourceName.isNullOrBlank()) {
            return ToolExecuteResult.Data("""{"error":"source_name 参数不能为空"}""")
        }
        val source = appDb.rssSourceDao.all.find { it.sourceName == sourceName }
            ?: return ToolExecuteResult.Data("""{"error":"未找到订阅源: $sourceName"}""")
        return ToolExecuteResult.NeedConfirmation(
            description = "删除订阅源「${source.sourceName}」（此操作不可撤销）"
        ) {
            withContext(Dispatchers.IO) {
                SourceHelp.deleteRssSource(source.sourceUrl)
            }
            """{"success":true,"message":"已删除订阅源「${source.sourceName}」"}"""
        }
    }

    // ========== 工具方法 ==========

    private fun bookToSimple(book: Book): Map<String, Any?> {
        val groupNames = try {
            appDb.bookGroupDao.getGroupNames(book.group)
        } catch (_: Exception) {
            emptyList()
        }
        return mapOf(
            "name" to book.name,
            "author" to book.author,
            "group" to groupNames,
            "durChapter" to (book.durChapterTitle ?: ""),
            "latestChapter" to (book.latestChapterTitle ?: ""),
            "canUpdate" to book.canUpdate,
            "lastReadTime" to book.durChapterTime
        )
    }
}
