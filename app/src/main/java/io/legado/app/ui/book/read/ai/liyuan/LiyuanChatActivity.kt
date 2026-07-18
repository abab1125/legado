package io.legado.app.ui.book.read.ai.liyuan

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.databinding.ActivityLiyuanChatBinding
import io.legado.app.help.config.AiConfig
import io.legado.app.ui.book.read.ai.liyuan.LiyuanWsClient.ConnectionState
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.lib.dialogs.alert

/**
 * 梨园对话主界面
 *
 * 与梨园后端通过 WebSocket wire 协议通信，实现 AI 角色扮演对话。
 *
 * 生命周期：
 *   1. onActivityCreated → 初始化 wsClient、绑定 LiveData、连接
 *   2. onDestroy → 断开连接
 */
class LiyuanChatActivity : BaseActivity<ActivityLiyuanChatBinding>() {

    override val binding by viewBinding(ActivityLiyuanChatBinding::inflate)
    private lateinit var viewModel: LiyuanChatViewModel
    private lateinit var adapter: LiyuanChatAdapter

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        viewModel = LiyuanChatViewModel(application)
        val wsClient = viewModel.wsClient
        val wsUrl = AiConfig.liyuanWsUrl

        // 初始化适配器
        adapter = LiyuanChatAdapter()
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        // === 绑定 LiveData ===

        // 消息列表变化 → 刷新 RecyclerView
        wsClient.messagesLiveData.observe(this, Observer { messages ->
            adapter.submitList(messages)
            if (messages.isNotEmpty()) {
                binding.recyclerView.smoothScrollToPosition(messages.size - 1)
            }
            // 如果有完整消息到达，清空 delta
            viewModel.onMessageReceived()
        })

        // 连接状态变化
        wsClient.connectionStateLiveData.observe(this, Observer { state ->
            updateConnectionIndicator(state)
        })

        // 流式增量文本 → 追加到当前气泡
        wsClient.streamingDeltaLiveData.observe(this, Observer { delta ->
            viewModel.onDelta(delta)
            adapter.appendDelta(viewModel.displayDeltaLiveData.value ?: "")
            val count = adapter.itemCount
            if (count > 0) binding.recyclerView.smoothScrollToPosition(count - 1)
        })

        // 决策卡弹窗
        wsClient.pendingChoiceLiveData.observe(this, Observer { choice ->
            if (choice != null) {
                showChoiceDialog(choice)
            } else {
                supportFragmentManager.fragments
                    .filterIsInstance<LiyuanChoiceDialog>()
                    .forEach { it.dismiss() }
            }
        })

        // 流式状态 → 切换按钮
        wsClient.isStreamingLiveData.observe(this, Observer { streaming ->
            binding.btnSend.isEnabled = !streaming
            binding.btnStop.isEnabled = streaming
        })

        // 错误/通知消息
        wsClient.errorLiveData.observe(this, Observer { error ->
            if (error != null) toastOnUi(error)
        })

        // === 按钮事件 ===

        binding.btnSend.setOnClickListener {
            val text = binding.etInput.text?.toString()?.trim() ?: return@setOnClickListener
            if (text.isBlank()) return@setOnClickListener
            wsClient.sendPrompt(text)
            binding.etInput.text?.clear()
        }

        binding.btnStop.setOnClickListener {
            wsClient.abort()
        }

        // 连接
        viewModel.connect(wsUrl)
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_liyuan_chat, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_liyuan_settings -> {
                val currentUrl = AiConfig.liyuanWsUrl
                val input = android.widget.EditText(this).apply {
                    setText(currentUrl)
                    selectAll()
                    setSingleLine()
                }
                alert("梨园服务器地址") {
                    customView { input }
                    okButton {
                        val newUrl = input.text.toString().trim()
                        if (newUrl.isNotBlank() && newUrl != currentUrl) {
                            AiConfig.liyuanWsUrl = newUrl
                            viewModel.disconnect()
                            viewModel.connect(newUrl)
                            toastOnUi("已更新服务器地址")
                        }
                    }
                    cancelButton { }
                }
            }
            else -> return super.onCompatOptionsItemSelected(item)
        }
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        viewModel.disconnect()
    }

    private fun updateConnectionIndicator(state: ConnectionState) {
        val text = when (state) {
            ConnectionState.CONNECTED -> "已连接"
            ConnectionState.CONNECTING -> "连接中…"
            ConnectionState.DISCONNECTED -> "未连接"
        }
        binding.titleBar.subtitle = text
    }

    private fun showChoiceDialog(choice: LiyuanWsClient.ChoiceFrame) {
        val dialog = LiyuanChoiceDialog.newInstance(
            id = choice.id,
            question = choice.question,
            options = choice.options,
            placeholder = choice.placeholder
        )
        dialog.onReply = { value, stop ->
            viewModel.wsClient.choiceReply(choice.id, value, stop)
        }
        dialog.show(supportFragmentManager, "liyuan_choice")
    }
}
