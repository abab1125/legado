package io.legado.app.ui.book.read.ai

import androidx.lifecycle.MutableLiveData

/**
 * AI 工具状态广播总线。
 *
 * ToolRouter（全局 object）在工具执行时把状态投递到这里，
 * 写作页面板 / 编辑器观察对应 LiveData 即可，避免 ToolRouter 直接依赖 ViewModel。
 *
 * 用途：
 * - toolActivityLiveData：灵犀式状态卡（读取章节 N 字 / 写入章节 等）
 * - chapterUpdatedLiveData：章节正文被 Agent 改写后通知编辑器自动刷新（免保存标记）
 */
object AiToolStatusBus {

    /**
     * 工具活动状态
     * kind: tool_start | tool_end | note
     */
    data class ToolActivity(
        val kind: String,
        val name: String,
        val detail: String = ""
    )

    val toolActivityLiveData = MutableLiveData<ToolActivity>()

    /** 值为 bookUrl，编辑器据此重载当前章节正文 */
    val chapterUpdatedLiveData = MutableLiveData<String>()

    fun postToolActivity(kind: String, name: String, detail: String = "") {
        toolActivityLiveData.postValue(ToolActivity(kind, name, detail))
    }

    fun postChapterUpdated(bookUrl: String) {
        chapterUpdatedLiveData.postValue(bookUrl)
    }
}
