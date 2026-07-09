package io.legado.app.ui.book.bookmark

import android.content.Context
import android.view.ViewGroup
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.entities.Bookmark
import io.legado.app.databinding.ItemBookmarkBinding
import io.legado.app.utils.gone
import splitties.views.onClick
import splitties.views.onLongClick

import io.legado.app.data.entities.BookThought

data class MarkItem(
    val bookName: String,
    val bookAuthor: String,
    val chapterName: String,
    val text: String,
    val content: String,
    val time: Long,
    val bookmark: Bookmark? = null,
    val thought: BookThought? = null
)

class BookmarkAdapter(context: Context, val callback: Callback) :
    RecyclerAdapter<MarkItem, ItemBookmarkBinding>(context) {

    override fun getViewBinding(parent: ViewGroup): ItemBookmarkBinding {
        return ItemBookmarkBinding.inflate(inflater, parent, false)
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemBookmarkBinding,
        item: MarkItem,
        payloads: MutableList<Any>
    ) {
        binding.tvChapterName.text = item.chapterName
        binding.tvBookText.gone(item.text.isEmpty())
        binding.tvBookText.text = item.text
        binding.tvContent.gone(item.content.isEmpty())
        binding.tvContent.text = item.content
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemBookmarkBinding) {
        binding.root.onClick {
            getItemByLayoutPosition(holder.layoutPosition)?.let {
                callback.onItemClick(it, holder.layoutPosition)
            }
        }
        binding.root.onLongClick {
            getItemByLayoutPosition(holder.layoutPosition)?.let {
                callback.onItemLongClick(it, holder.layoutPosition)
            } ?: false
        }
    }

    fun getHeaderText(position: Int): String {
        return with(getItem(position)) {
            "${this?.bookName ?: ""}(${this?.bookAuthor ?: ""})"
        }
    }

    fun isItemHeader(position: Int): Boolean {
        if (position == 0) return true
        val lastItem = getItem(position - 1)
        val curItem = getItem(position)
        return !(lastItem?.bookName == curItem?.bookName
                && lastItem?.bookAuthor == curItem?.bookAuthor)
    }

    interface Callback {
        fun onItemClick(item: MarkItem, position: Int)
        fun onItemLongClick(item: MarkItem, position: Int): Boolean
    }

}