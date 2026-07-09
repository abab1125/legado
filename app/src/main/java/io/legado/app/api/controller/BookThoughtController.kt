package io.legado.app.api.controller

import io.legado.app.api.ReturnData
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookThought
import io.legado.app.ui.book.thought.ThoughtObsidianExporter
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject

object BookThoughtController {

    /**
     * 获取某本书的所有想法
     */
    fun getBookThoughts(parameters: Map<String, List<String>>): ReturnData {
        val returnData = ReturnData()
        val bookName = parameters["bookName"]?.firstOrNull()
            ?: return returnData.setErrorMsg("参数bookName不能为空")
        val bookAuthor = parameters["bookAuthor"]?.firstOrNull()
            ?: return returnData.setErrorMsg("参数bookAuthor不能为空")
        val thoughts = appDb.bookThoughtDao.getByBook(bookName, bookAuthor)
        return returnData.setData(thoughts)
    }

    /**
     * 获取某章节的想法
     */
    fun getThoughtsByChapter(parameters: Map<String, List<String>>): ReturnData {
        val returnData = ReturnData()
        val bookName = parameters["bookName"]?.firstOrNull()
            ?: return returnData.setErrorMsg("参数bookName不能为空")
        val bookAuthor = parameters["bookAuthor"]?.firstOrNull()
            ?: return returnData.setErrorMsg("参数bookAuthor不能为空")
        val index = parameters["index"]?.firstOrNull()?.toIntOrNull()
            ?: return returnData.setErrorMsg("参数index不能为空, 请指定章节序号")
        val thoughts = appDb.bookThoughtDao.getByChapter(bookName, bookAuthor, index)
        return returnData.setData(thoughts)
    }

    /**
     * 保存想法（新建或更新）
     */
    fun saveBookThought(postData: String?): ReturnData {
        val returnData = ReturnData()
        GSON.fromJsonObject<BookThought>(postData).getOrNull()?.let { thought ->
            if (thought.id == 0L) {
                appDb.bookThoughtDao.insert(thought)
            } else {
                appDb.bookThoughtDao.update(thought)
            }
            ThoughtObsidianExporter.exportBookAsync(thought.bookName, thought.bookAuthor)
            return returnData.setData("")
        }
        return returnData.setErrorMsg("格式不对")
    }

    /**
     * 删除想法
     */
    fun deleteBookThought(postData: String?): ReturnData {
        val returnData = ReturnData()
        GSON.fromJsonObject<BookThought>(postData).getOrNull()?.let { thought ->
            appDb.bookThoughtDao.delete(thought)
            ThoughtObsidianExporter.exportBookAsync(thought.bookName, thought.bookAuthor)
            return returnData.setData("")
        }
        return returnData.setErrorMsg("格式不对")
    }

}
