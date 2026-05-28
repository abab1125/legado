package io.legado.app.ui.book.search

import android.view.ViewGroup
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.entities.SearchKeyword
import io.legado.app.databinding.ItemSearchHistoryBinding

class HistoryKeyAdapter(activity: SearchActivity, val callBack: CallBack) :
    RecyclerAdapter<SearchKeyword, ItemSearchHistoryBinding>(activity) {

    override fun getItemId(position: Int): Long {
        return position.toLong()
    }

    override fun getViewBinding(parent: ViewGroup): ItemSearchHistoryBinding {
        return ItemSearchHistoryBinding.inflate(inflater, parent, false)
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemSearchHistoryBinding,
        item: SearchKeyword,
        payloads: MutableList<Any>
    ) {
        binding.run {
            tvKeyword.text = item.word
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemSearchHistoryBinding) {
        binding.tvKeyword.setOnClickListener {
            getItemByLayoutPosition(holder.layoutPosition)?.let {
                callBack.searchHistory(it.word)
            }
        }
        binding.ivDelete.setOnClickListener {
            getItemByLayoutPosition(holder.layoutPosition)?.let {
                callBack.deleteHistory(it)
            }
        }
    }

    interface CallBack {
        fun searchHistory(key: String)
        fun deleteHistory(searchKeyword: SearchKeyword)
    }
}
