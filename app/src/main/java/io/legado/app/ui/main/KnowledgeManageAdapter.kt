package io.legado.app.ui.main

import android.content.Context
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import io.legado.app.base.adapter.DiffRecyclerAdapter
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.data.entities.KnowledgePoint
import io.legado.app.databinding.ItemKnowledgeBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class KnowledgeManageAdapter(
    context: Context,
    private val onItemClick: (KnowledgePoint) -> Unit
) : DiffRecyclerAdapter<KnowledgePoint, ItemKnowledgeBinding>(context) {

    override val diffItemCallback: DiffUtil.ItemCallback<KnowledgePoint>
        get() = object : DiffUtil.ItemCallback<KnowledgePoint>() {
            override fun areItemsTheSame(oldItem: KnowledgePoint, newItem: KnowledgePoint) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: KnowledgePoint, newItem: KnowledgePoint) = oldItem == newItem
        }

    override fun getViewBinding(parent: ViewGroup): ItemKnowledgeBinding {
        return ItemKnowledgeBinding.inflate(inflater, parent, false)
    }

    override fun convert(holder: ItemViewHolder, binding: ItemKnowledgeBinding, item: KnowledgePoint, payloads: MutableList<Any>) {
        binding.run {
            tvTitle.text = item.title
            tvContent.text = item.content
            tvTag.text = getCategoryLabel(item.category)
            tvTime.text = formatTime(item.updateTime)
            ivEdit.setOnClickListener { onItemClick(item) }
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemKnowledgeBinding) {
        holder.itemView.setOnClickListener {
            getItem(holder.layoutPosition)?.let { onItemClick(it) }
        }
    }

    private fun getCategoryLabel(category: String): String = when (category) {
        "character" -> "人物"
        "place" -> "地点"
        "event" -> "事件"
        "note" -> "笔记"
        else -> "其他"
    }

    private fun formatTime(timestamp: Long): String {
        if (timestamp == 0L) return ""
        val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }
}
