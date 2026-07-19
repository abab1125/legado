package io.legado.app.ui.book.read.ai

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.widget.Toast
import androidx.core.graphics.ColorUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.RotateAnimation
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.databinding.ItemAiChatBinding
import io.legado.app.help.config.AiConfig
import io.legado.app.help.glide.ImageLoader
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.utils.gone
import io.legado.app.utils.visible
import io.noties.markwon.Markwon
import java.net.URI

class ChatAdapter(
    private val onEditMessage: (position: Int, currentContent: String) -> Unit,
    private val onDeleteMessage: (position: Int) -> Unit
) : ListAdapter<ChatMessage, ChatAdapter.ChatViewHolder>(DIFF_CALLBACK) {

    private var markwon: Markwon? = null
    private val dotAnimators = mutableMapOf<Int, ValueAnimator>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
        val binding = ItemAiChatBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ChatViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
        val msg = getItem(position)
        val context = holder.binding.root.context

        if (markwon == null) {
            markwon = Markwon.create(context)
        }

        // 工具状态消息：居中灰条，不渲染气泡
        if (msg.type == "tool_status") {
            holder.binding.llUserMsg.gone()
            holder.binding.llAiMsg.visible()
            holder.binding.cardAiBubble.visibility = View.GONE
            holder.binding.loadingDots.visibility = View.GONE
            holder.binding.llAiActions.visibility = View.GONE
            holder.binding.llReasoning.gone()
            holder.binding.tvAiContent.visible()
            holder.binding.tvAiContent.textAlignment = View.TEXT_ALIGNMENT_CENTER
            holder.binding.tvAiContent.setTextColor(android.graphics.Color.parseColor("#888888"))
            holder.binding.tvAiContent.textSize = 12f
            holder.binding.tvAiContent.text = msg.content ?: ""
            return
        }

        if (msg.role == "user") {
            holder.binding.llUserMsg.visible()
            holder.binding.llAiMsg.gone()
            markwon?.setMarkdown(holder.binding.tvUserContent, msg.content ?: "")
            if (AiConfig.userAvatar.isNotBlank()) {
                ImageViewCompat.setImageTintList(holder.binding.ivUserAvatar, null)
                ImageLoader.load(context, encodeAvatarUrl(AiConfig.userAvatar)).into(holder.binding.ivUserAvatar)
            } else {
                ImageViewCompat.setImageTintList(
                    holder.binding.ivUserAvatar,
                    ColorStateList.valueOf(ThemeStore.primaryColor(context))
                )
                holder.binding.ivUserAvatar.setImageResource(R.drawable.ic_person)
            }

            val userBubbleColor = ColorUtils.setAlphaComponent(
                ThemeStore.primaryColor(context), 45
            )
            holder.binding.cardUserBubble.setCardBackgroundColor(userBubbleColor)

            holder.binding.btnUserEdit.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onEditMessage(pos, msg.content ?: "")
            }
            holder.binding.btnUserCopy.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    copyToClipboard(context, msg.content)
                }
            }
            holder.binding.btnUserDelete.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onDeleteMessage(pos)
            }
        } else {
            holder.binding.llAiMsg.visible()
            holder.binding.llUserMsg.gone()
            holder.binding.cardAiBubble.visibility = View.GONE
            holder.binding.loadingDots.visibility = View.GONE
            holder.binding.llAiActions.visibility = View.GONE

            if (msg.isStreaming && msg.content.isNullOrBlank()) {
                // 流式开始，还没有内容 → 显示三点动画
                holder.binding.loadingDots.visibility = View.VISIBLE
                startDotAnimation(holder)
            } else {
                // 有内容（流式或完成）
                if (msg.isStreaming) stopDotAnimation(holder)
                holder.binding.cardAiBubble.visibility = View.VISIBLE
                markwon?.setMarkdown(holder.binding.tvAiContent, msg.content ?: "")

                // 流式结束后显示操作栏
                if (!msg.isStreaming) {
                    holder.binding.llAiActions.visibility = View.VISIBLE
                }
            }

            if (AiConfig.aiAvatar.isNotBlank()) {
                ImageViewCompat.setImageTintList(holder.binding.ivAiAvatar, null)
                ImageLoader.load(context, encodeAvatarUrl(AiConfig.aiAvatar)).into(holder.binding.ivAiAvatar)
            } else {
                ImageViewCompat.setImageTintList(
                    holder.binding.ivAiAvatar,
                    ColorStateList.valueOf(ThemeStore.primaryColor(context))
                )
                holder.binding.ivAiAvatar.setImageResource(R.drawable.ic_chat_ai)
            }

            val reasoning = msg.reasoningContent
            if (!reasoning.isNullOrBlank()) {
                holder.binding.llReasoning.visible()
                markwon?.setMarkdown(holder.binding.tvReasoningContent, reasoning)
                holder.binding.tvReasoningContent.gone()
                holder.binding.ivReasoningArrow.rotation = 90f
                holder.binding.llReasoningHeader.setOnClickListener {
                    val isExpanded = holder.binding.tvReasoningContent.visibility == View.VISIBLE
                    if (isExpanded) collapseReasoning(holder) else expandReasoning(holder)
                }
            } else {
                holder.binding.llReasoning.gone()
            }

            holder.binding.btnAiEdit.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onEditMessage(pos, msg.content ?: "")
            }
            holder.binding.btnAiCopy.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    copyToClipboard(context, msg.content)
                }
            }
            holder.binding.btnAiDelete.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onDeleteMessage(pos)
            }
        }
    }

    private fun copyToClipboard(context: Context, text: String?) {
        val clipManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipManager.setPrimaryClip(ClipData.newPlainText("ai_message", text ?: ""))
        Toast.makeText(context, R.string.ai_copied, Toast.LENGTH_SHORT).show()
    }

    private fun expandReasoning(holder: ChatViewHolder) {
        holder.binding.tvReasoningContent.visible()
        animateArrow(holder.binding.ivReasoningArrow, 90f, 270f)
    }

    private fun collapseReasoning(holder: ChatViewHolder) {
        holder.binding.tvReasoningContent.gone()
        animateArrow(holder.binding.ivReasoningArrow, 270f, 90f)
    }

    private fun animateArrow(view: android.widget.ImageView, from: Float, to: Float) {
        val anim = RotateAnimation(
            from, to,
            Animation.RELATIVE_TO_SELF, 0.5f,
            Animation.RELATIVE_TO_SELF, 0.5f
        ).apply {
            duration = 200
            fillAfter = true
        }
        view.startAnimation(anim)
    }

    private fun startDotAnimation(holder: ChatViewHolder) {
        val pos = holder.bindingAdapterPosition
        if (pos == RecyclerView.NO_POSITION) return
        // 取消已有动画
        stopDotAnimation(holder)
        val dots = listOf(
            holder.binding.dot1,
            holder.binding.dot2,
            holder.binding.dot3
        )
        dots.forEachIndexed { index, dot ->
            val anim = ObjectAnimator.ofFloat(dot, "alpha", 0.3f, 1.0f).apply {
                duration = 700
                startDelay = (index * 200).toLong()
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                start()
            }
            dotAnimators[pos] = anim
        }
    }

    private fun stopDotAnimation(holder: ChatViewHolder) {
        val pos = holder.bindingAdapterPosition
        dotAnimators.remove(pos)?.cancel()
    }

    override fun onViewDetachedFromWindow(holder: ChatViewHolder) {
        super.onViewDetachedFromWindow(holder)
        stopDotAnimation(holder)
    }

    class ChatViewHolder(val binding: ItemAiChatBinding) : RecyclerView.ViewHolder(binding.root)

    companion object {
        fun encodeAvatarUrl(url: String): String {
            return try {
                val uri = URI(url)
                URI(
                    uri.scheme, uri.userInfo, uri.host, uri.port,
                    uri.path, uri.query, uri.fragment
                ).toASCIIString()
            } catch (e: Exception) {
                url
            }
        }

        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<ChatMessage>() {
            override fun areItemsTheSame(old: ChatMessage, new: ChatMessage): Boolean {
                return old.id != null && new.id != null && old.id == new.id
            }

            override fun areContentsTheSame(old: ChatMessage, new: ChatMessage): Boolean {
                return old == new
            }
        }
    }
}
