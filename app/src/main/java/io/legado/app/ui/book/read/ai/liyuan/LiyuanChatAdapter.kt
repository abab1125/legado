package io.legado.app.ui.book.read.ai.liyuan

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R

/**
 * 梨园消息列表适配器
 *
 * 展示 WireMsgDto 列表。
 * narrative/backstage → 左对齐（角色消息）
 * user → 右对齐（用户消息）
 */
class LiyuanChatAdapter : RecyclerView.Adapter<LiyuanChatAdapter.ViewHolder>() {

    private var messages: List<LiyuanWsClient.WireMsgDto> = emptyList()
    private var deltaText: String = ""

    override fun getItemCount(): Int = messages.size + if (deltaText.isNotEmpty()) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_liyuan_msg, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val isDelta = position == messages.size && deltaText.isNotEmpty()
        val msg = if (isDelta) null else messages.getOrNull(position)

        holder.tvContent.text = if (isDelta) {
            deltaText
        } else {
            msg?.text ?: ""
        }

        holder.tvName.text = when {
            isDelta -> (messages.lastOrNull()?.name ?: "青梧") + "（输入中…）"
            msg != null && msg.channel == "user" -> "你"
            msg?.name != null -> msg.name
            else -> "青梧"
        }

        // 对齐方式
        val isUser = msg?.channel == "user" && !isDelta
        val lp = holder.tvContent.layoutParams as? ViewGroup.MarginLayoutParams
        if (isUser) {
            lp?.marginStart = holder.itemView.resources.getDimensionPixelSize(R.dimen.msg_margin_user)
            lp?.marginEnd = 0
        } else {
            lp?.marginStart = 0
            lp?.marginEnd = holder.itemView.resources.getDimensionPixelSize(R.dimen.msg_margin_char)
        }
    }

    class ViewHolder(view: android.view.View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tvName)
        val tvContent: TextView = view.findViewById(R.id.tvContent)
    }

    fun submitList(list: List<LiyuanWsClient.WireMsgDto>) {
        messages = list
        deltaText = ""
        notifyDataSetChanged()
    }

    fun appendDelta(delta: String) {
        deltaText = delta
        notifyItemChanged(messages.size)
    }
}
