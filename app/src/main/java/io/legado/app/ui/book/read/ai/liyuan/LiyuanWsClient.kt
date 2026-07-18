package io.legado.app.ui.book.read.ai.liyuan

import io.legado.app.help.http.okHttpClient
import io.legado.app.utils.GSON
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.*
import okhttp3.*

/**
 * LiyuanWsClient：梨园 WebSocket 客户端
 *
 * 职责：
 * 1. 连接/断连/自动重连（指数退避，1s→2s→4s→8s→10s 封顶）
 * 2. 收发 wire 协议 JSON 帧
 * 3. 将服务端帧解析为 LiveData 供 UI 层消费
 * 4. 心跳保持
 *
 * 使用方法：
 *   val client = LiyuanWsClient()
 *   client.connect("ws://101.37.119.146/liyuan/ws")
 *   client.connectionStateLiveData.observe(this) { state -> ... }
 *   client.messagesLiveData.observe(this) { messages -> adapter.submitList(messages) }
 *   client.sendPrompt("你好")
 *   client.disconnect()   // Activity onDestroy 时
 */
class LiyuanWsClient {

    // ===== LiveData（UI 层通过 observe 订阅）=====

    /** 连接状态 */
    val connectionStateLiveData = MutableLiveData(ConnectionState.DISCONNECTED)

    /** 消息列表（收到 hello 全量替换，收到 message 追加） */
    val messagesLiveData = MutableLiveData<List<WireMsgDto>>(emptyList())

    /** 最新帧类型 */
    val lastFrameTypeLiveData = MutableLiveData<String?>(null)

    /** 待处理的决策卡（UI 层 observe 到非 null 就弹窗） */
    val pendingChoiceLiveData = MutableLiveData<ChoiceFrame?>(null)

    /** 流式增量文本（delta 累积） */
    val streamingDeltaLiveData = MutableLiveData<String>("")

    /** 是否正在流式生成 */
    val isStreamingLiveData = MutableLiveData(false)

    /** 错误/通知消息 */
    val errorLiveData = MutableLiveData<String?>(null)

    /** 会话 ID（hello 帧获取） */
    var sessionId: String? = null; private set
    /** 当前角色名 */
    var currentCharName: String? = null; private set
    /** 当前用户名 */
    var currentUserName: String? = null; private set

    // ===== 内部状态 =====

    private var ws: WebSocket? = null
    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var reconnectJob: Job? = null
    private var heartbeatJob: Job? = null
    private var retryCount = 0
    private var currentUrl: String? = null
    private var isDisconnectByUser = false

    // ===== 公开方法 =====

    /**
     * 连接 WebSocket 服务器
     * @param url 完整 ws:// 地址
     */
    fun connect(url: String) {
        currentUrl = url
        isDisconnectByUser = false
        connectionStateLiveData.postValue(ConnectionState.CONNECTING)
        val request = Request.Builder().url(url).build()
        okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                retryCount = 0
                connectionStateLiveData.postValue(ConnectionState.CONNECTED)
                this@LiyuanWsClient.ws = ws
                startHeartbeat()
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleFrame(text)
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                ws.close(1000, null)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                connectionStateLiveData.postValue(ConnectionState.DISCONNECTED)
                this@LiyuanWsClient.ws = null
                stopHeartbeat()
                if (!isDisconnectByUser && code != 1000) {
                    scheduleReconnect()
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                t.printStackTrace()
                connectionStateLiveData.postValue(ConnectionState.DISCONNECTED)
                this@LiyuanWsClient.ws = null
                stopHeartbeat()
                if (!isDisconnectByUser) {
                    errorLiveData.postValue("连接失败：${t.message ?: "未知错误"}")
                    scheduleReconnect()
                }
            }
        })
    }

    /**
     * 主动断开连接
     */
    fun disconnect() {
        isDisconnectByUser = true
        reconnectJob?.cancel()
        reconnectJob = null
        stopHeartbeat()
        ws?.close(1000, "user disconnect")
        ws = null
        connectionStateLiveData.postValue(ConnectionState.DISCONNECTED)
    }

    /** 发送用户消息 */
    fun sendPrompt(text: String) {
        if (text.isBlank()) return
        if (ws == null) {
            errorLiveData.postValue("未连接，无法发送")
            return
        }
        sendFrame(mapOf("type" to "prompt", "text" to text))
        // 用户消息也加入本地消息列表
        val msg = WireMsgDto(
            channel = "user",
            text = text,
            name = currentUserName
        )
        val current = messagesLiveData.value?.toMutableList() ?: mutableListOf()
        current.add(msg)
        messagesLiveData.postValue(current)
    }

    /** 停止生成 */
    fun abort() {
        sendFrame(mapOf("type" to "abort"))
    }

    /** 重生成最后一轮 */
    fun reroll(text: String? = null) {
        val frame = mutableMapOf<String, Any?>("type" to "reroll")
        if (text != null) frame["text"] = text
        sendFrame(frame)
    }

    /** 变体导航 */
    fun swipe(dir: String) {
        sendFrame(mapOf("type" to "swipe", "dir" to dir))
    }

    /** 决策卡答复 */
    fun choiceReply(id: String, value: String? = null, stop: Boolean = false) {
        val frame = mutableMapOf<String, Any?>("type" to "choice_reply", "id" to id)
        if (value != null) frame["value"] = value
        if (stop) frame["stop"] = true
        sendFrame(frame)
        pendingChoiceLiveData.postValue(null)
    }

    /** 获取会话列表 */
    fun requestSessions() {
        sendFrame(mapOf("type" to "sessions"))
    }

    /** 打开特定会话 */
    fun openSession(path: String) {
        sendFrame(mapOf("type" to "open", "path" to path))
    }

    // ===== 内部方法 =====

    /**
     * 发送 JSON 帧
     */
    private fun sendFrame(frame: Map<String, Any?>) {
        val json = GSON.toJson(frame)
        ws?.send(json)
    }

    /**
     * 处理服务端帧文本
     */
    @Suppress("UNCHECKED_CAST")
    private fun handleFrame(text: String) {
        try {
            val frame = GSON.fromJson(text, Map::class.java) as? Map<String, Any?> ?: return
            val type = frame["type"] as? String ?: return
            lastFrameTypeLiveData.postValue(type)

            when (type) {
                "hello" -> handleHello(frame)
                "message" -> handleMessage(frame)
                "delta" -> handleDelta(frame)
                "stream" -> handleStream(frame)
                "agent" -> handleAgent(frame)
                "activity" -> handleActivity(frame)
                "choice" -> handleChoice(frame)
                "choice_resolved" -> handleChoiceResolved(frame)
                "notify" -> handleNotify(frame)
                "error" -> handleError(frame)
                "sessions" -> handleSessions(frame)
                // assistant_xxx 帧阶段2实现；其他帧忽略
            }
        } catch (e: Exception) {
            e.printStackTrace()
            errorLiveData.postValue("帧解析错误：${e.message}")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun handleHello(frame: Map<String, Any?>) {
        sessionId = frame["sessionId"] as? String
        currentCharName = frame["charName"] as? String
        currentUserName = frame["userName"] as? String

        val rawMessages = frame["messages"] as? List<Map<String, Any?>> ?: emptyList()
        val parsed = rawMessages.mapNotNull { parseWireMsg(it) }

        // hello 帧必须全量替换，不能追加！
        messagesLiveData.postValue(parsed)
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseWireMsg(map: Map<String, Any?>): WireMsgDto? {
        return WireMsgDto(
            channel = map["channel"] as? String ?: "narrative",
            text = map["text"] as? String ?: "",
            name = map["name"] as? String,
            avatar = map["avatar"] as? String,
            src = map["src"] as? String,
            thinking = map["thinking"] as? String,
            backstage = map["backstage"] as? Boolean ?: false
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun handleMessage(frame: Map<String, Any?>) {
        val rawMsg = frame["message"] as? Map<String, Any?> ?: return
        val parsed = parseWireMsg(rawMsg) ?: return
        val current = messagesLiveData.value?.toMutableList() ?: mutableListOf()
        current.add(parsed)
        messagesLiveData.postValue(current)
    }

    private fun handleDelta(frame: Map<String, Any?>) {
        val delta = frame["delta"] as? String ?: return
        streamingDeltaLiveData.postValue(delta)
    }

    private fun handleStream(frame: Map<String, Any?>) {
        val state = frame["state"] as? String
        if (state == "clear") {
            // 清空流式缓冲区标记
            streamingDeltaLiveData.postValue("__CLEAR__")
        }
    }

    private fun handleAgent(frame: Map<String, Any?>) {
        val state = frame["state"] as? String
        isStreamingLiveData.postValue(state == "start")
    }

    @Suppress("UNCHECKED_CAST")
    private fun handleActivity(frame: Map<String, Any?>) {
        // 阶段2实现：显示活动状态
        val activity = frame["activity"] as? Map<String, Any?>
        val name = activity?.get("name") as? String
        if (name != null) {
            errorLiveData.postValue("[活动] $name")  // 临时用 error channel 显示
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun handleChoice(frame: Map<String, Any?>) {
        val id = frame["id"] as? String ?: return
        val question = frame["question"] as? String ?: ""
        val rawOptions = frame["options"]
        val optionList: List<String> = when (rawOptions) {
            is List<*> -> rawOptions.mapNotNull { it?.toString() }
            else -> emptyList()
        }
        val placeholder = frame["placeholder"] as? String
        pendingChoiceLiveData.postValue(ChoiceFrame(id, question, optionList, placeholder))
    }

    private fun handleChoiceResolved(frame: Map<String, Any?>) {
        pendingChoiceLiveData.postValue(null)
    }

    private fun handleNotify(frame: Map<String, Any?>) {
        val text = frame["text"] as? String ?: return
        errorLiveData.postValue("[通知] $text")
    }

    private fun handleError(frame: Map<String, Any?>) {
        val text = frame["text"] as? String ?: "未知错误"
        errorLiveData.postValue(text)
    }

    @Suppress("UNCHECKED_CAST")
    private fun handleSessions(frame: Map<String, Any?>) {
        // 阶段2实现：会话列表
    }

    // ===== 心跳 =====

    /** 心跳：每 30s 发一条空消息防止中间设备断连 */
    private fun startHeartbeat() {
        stopHeartbeat()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(30_000)
                // OkHttp WebSocket 没有标准 ping API，发一个空 JSON 帧
                ws?.send("{}")
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    // ===== 自动重连 =====

    /** 指数退避重连，1s→2s→4s→8s→10s 封顶 */
    private fun scheduleReconnect() {
        val delay = (1000L * (1 shl retryCount.coerceAtMost(4))).coerceAtMost(10000L)
        retryCount++
        val url = currentUrl ?: return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(delay)
            connect(url)
        }
    }

    // ===== 数据类 =====

    data class WireMsgDto(
        val channel: String,      // "narrative" | "user" | "backstage" | ...
        val text: String,
        val name: String? = null,
        val avatar: String? = null,
        val src: String? = null,
        val thinking: String? = null,
        val backstage: Boolean = false
    )

    data class ChoiceFrame(
        val id: String,
        val question: String,
        val options: List<String>,
        val placeholder: String? = null
    )

    enum class ConnectionState {
        DISCONNECTED, CONNECTING, CONNECTED
    }
}
