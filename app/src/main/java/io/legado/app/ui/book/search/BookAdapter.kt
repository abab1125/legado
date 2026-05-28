package io.legado.app.ui.book.search

import android.content.Context
import android.view.ViewGroup
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.entities.Book
import io.legado.app.databinding.ItemBookshelfPillBinding


class BookAdapter(context: Context, val callBack: CallBack) :
    RecyclerAdapter<Book, ItemBookshelfPillBinding>(context) {

    override fun getItemId(position: Int): Long {
        return position.toLong()
    }

    override fun getViewBinding(parent: ViewGroup): ItemBookshelfPillBinding {
        return ItemBookshelfPillBinding.inflate(inflater, parent, false)
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemBookshelfPillBinding,
        item: Book,
        payloads: MutableList<Any>
    ) {
        binding.run {
            textView.text = item.name
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemBookshelfPillBinding) {
        holder.itemView.apply {
            setOnClickListener {
                getItem(holder.layoutPosition)?.let {
                    callBack.showBookInfo(it)
                }
            }
        }
    }

    interface CallBack {
        fun showBookInfo(book: Book)
    }
}
