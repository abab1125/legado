package io.legado.app.ui.book.read.ai.liyuan

import android.app.Application
import androidx.lifecycle.MutableLiveData
import io.legado.app.base.BaseViewModel

/**
 * 梨园对话 ViewModel
 *
 * 职责：
 * 1. 持有 LiyuanWsClient 实例
 * 2. 管理消息列表（含 delta 累加逻辑）
 * 3. 暴露 LiveData 给 Activity
 */
class LiyuanChatViewModel(application: Application) : BaseViewModel(application) {

    val wsClient = LiyuanWsClient()

    /** 流式文本累加缓冲区 */
    private val deltaBuffer = StringBuilder()
    val displayDeltaLiveData = MutableLiveData<String>("")

    /** 当前流式状态 */
    val isStreamingLiveData = MutableLiveData(false)

    /**
     * 连接服务器
     */
    fun connect(wsUrl: String) {
        wsClient.connect(wsUrl)
    }

    /**
     * 断开连接
     */
    fun disconnect() {
        wsClient.disconnect()
    }

    /**
     * 处理 delta 累加
     * 收到 delta 帧时调用；stream:clear 清空缓冲区
     */
    fun onDelta(delta: String) {
        if (delta == "__CLEAR__") {
            deltaBuffer.clear()
            displayDeltaLiveData.postValue("")
            return
        }
        deltaBuffer.append(delta)
        displayDeltaLiveData.postValue(deltaBuffer.toString())
    }

    /**
     * 完整消息到达时清空 delta 缓冲区
     */
    fun onMessageReceived() {
        deltaBuffer.clear()
        displayDeltaLiveData.postValue("")
    }
}
