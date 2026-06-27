package io.legado.app.ui.main

import android.content.Context
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import io.legado.app.base.adapter.DiffRecyclerAdapter
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.data.entities.WritingPrompt
import io.legado.app.databinding.ItemPromptBinding
import io.legado.app.utils.toastOnUi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PromptManageAdapter(
    context: Context,
    private val onItemClick: (WritingPrompt) -> Unit,
    private val onDeleteClick: (WritingPrompt) -> Unit
) : DiffRecyclerAdapter<WritingPrompt, ItemPromptBinding>(context) {

    override val diffItemCallback: DiffUtil.ItemCallback<WritingPrompt>
        get() = object : DiffUtil.ItemCallback<WritingPrompt>() {
            override fun areItemsTheSame(oldItem: WritingPrompt, newItem: WritingPrompt) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: WritingPrompt, newItem: WritingPrompt) = oldItem == newItem
        }

    override fun getViewBinding(parent: ViewGroup): ItemPromptBinding {
        return ItemPromptBinding.inflate(inflater, parent, false)
    }

    override fun convert(holder: ItemViewHolder, binding: ItemPromptBinding, item: WritingPrompt, payloads: MutableList<Any>) {
        binding.run {
            tvTitle.text = item.title
            tvContent.text = item.content
            tvTag.text = getTypeLabel(item.type)
            tvTime.text = formatTime(item.updateTime)
            ivEdit.setOnClickListener { onItemClick(item) }
            ivDelete.setOnClickListener { onDeleteClick(item) }
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemPromptBinding) {
        holder.itemView.setOnClickListener {
            getItem(holder.layoutPosition)?.let { onItemClick(it) }
        }
    }

    private fun getTypeLabel(type: String): String = when (type) {
        "character" -> "角色设定"
        "world" -> "世界观"
        "style" -> "写作风格"
        "outline" -> "大纲"
        else -> "其他"
    }

    private fun formatTime(timestamp: Long): String {
        if (timestamp == 0L) return ""
        val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }
}
