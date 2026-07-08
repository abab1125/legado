package io.legado.app.ui.main

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import io.legado.app.R
import io.legado.app.base.adapter.DiffRecyclerAdapter
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.data.entities.KnowledgePoint
import io.legado.app.databinding.ItemKnowledgeBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 三级钻取适配器，支撑 DisplayItem 层级
 */
class KnowledgeManageAdapter(
    context: Context,
    private val onItemClick: (DisplayItem) -> Unit,
    private val onCollapseCategory: () -> Unit
) : DiffRecyclerAdapter<DisplayItem, ItemKnowledgeBinding>(context) {

    private var showCollapseBtn = false
    private var currentItems: List<DisplayItem> = emptyList()

    override val diffItemCallback: DiffUtil.ItemCallback<DisplayItem>
        get() = object : DiffUtil.ItemCallback<DisplayItem>() {
            override fun areItemsTheSame(oldItem: DisplayItem, newItem: DisplayItem): Boolean {
                return when {
                    oldItem is DisplayItem.Category && newItem is DisplayItem.Category ->
                        oldItem.category == newItem.category
                    oldItem is DisplayItem.NovelGroup && newItem is DisplayItem.NovelGroup ->
                        oldItem.novelName == newItem.novelName
                    oldItem is DisplayItem.Knowledge && newItem is DisplayItem.Knowledge ->
                        oldItem.point.id == newItem.point.id
                    else -> false
                }
            }

            override fun areContentsTheSame(oldItem: DisplayItem, newItem: DisplayItem): Boolean {
                return oldItem == newItem
            }
        }

    override fun getViewBinding(parent: ViewGroup): ItemKnowledgeBinding {
        return ItemKnowledgeBinding.inflate(inflater, parent, false)
    }

    fun setItems(items: List<DisplayItem>, showCollapse: Boolean) {
        showCollapseBtn = showCollapse
        currentItems = items
        submitList(items)
    }

    override fun convert(holder: ItemViewHolder, binding: ItemKnowledgeBinding, item: DisplayItem, payloads: MutableList<Any>) {
        when (item) {
            is DisplayItem.Category -> bindCategory(binding, item)
            is DisplayItem.NovelGroup -> bindNovelGroup(binding, item)
            is DisplayItem.Knowledge -> bindKnowledge(binding, item.point)
        }
    }

    private fun bindCategory(binding: ItemKnowledgeBinding, cat: DisplayItem.Category) {
        binding.tvTitle.text = getCategoryLabel(cat.category) + "  (${cat.count})"
        binding.tvContent.text = ""
        binding.tvTag.text = if (showCollapseBtn) "⇱ 收起" else ""
        binding.tvTime.text = getCategoryDesc(cat.category)
        // 一级分类不可点击编辑，但点击 tag 区域可折叠
    }

    private fun bindNovelGroup(binding: ItemKnowledgeBinding, group: DisplayItem.NovelGroup) {
        if (group.novelName.isEmpty()) {
            binding.tvTitle.text = "📌 经典角色  (${group.childCount})"
        } else {
            binding.tvTitle.text = "📖 ${group.novelName}  (${group.childCount})"
        }
        binding.tvContent.text = ""
        binding.tvTag.text = ""
        binding.tvTime.text = ""
    }

    private fun bindKnowledge(binding: ItemKnowledgeBinding, kp: KnowledgePoint) {
        binding.tvTitle.text = kp.title
        binding.tvContent.text = kp.content
        binding.tvTag.text = getCategoryLabel(kp.category)
        binding.tvTime.text = formatTime(kp.updateTime)
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemKnowledgeBinding) {
        holder.itemView.setOnClickListener {
            val pos = holder.layoutPosition
            if (pos >= 0 && pos < currentItems.size) {
                onItemClick(currentItems[pos])
            }
        }
        // 长按经典角色/小说角色可编辑
        holder.itemView.setOnLongClickListener {
            val pos = holder.layoutPosition
            if (pos >= 0 && pos < currentItems.size) {
                val item = currentItems[pos]
                if (item is DisplayItem.Knowledge) {
                    onItemClick(item)
                    return@setOnLongClickListener true
                }
            }
            false
        }
    }

    private fun getCategoryLabel(category: String): String = when (category) {
        "character" -> "人物"
        "place" -> "地点"
        "event" -> "事件"
        "note" -> "笔记"
        else -> "其他"
    }

    private fun getCategoryDesc(category: String): String = when (category) {
        "character" -> "角色卡·经典角色·小说角色"
        "place" -> "地图·地名·场景设定"
        "event" -> "重要事件·剧情脉络"
        "note" -> "创作笔记·设定备忘"
        else -> ""
    }

    private fun formatTime(timestamp: Long): String {
        if (timestamp == 0L) return ""
        val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }
}
