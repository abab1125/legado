package io.legado.app.ui.book.read.ai

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookChapter
import io.legado.app.databinding.DialogPickerChapterBinding
import io.legado.app.databinding.ItemPickerChapterBinding
import io.legado.app.utils.viewbindingdelegate.viewBinding

/**
 * 章节选择弹窗——在 AI 聊天中通过 @章节 引用某本书的章节
 */
class ChapterPickerDialog : BaseDialogFragment(R.layout.dialog_picker_chapter) {

    private val binding by viewBinding(DialogPickerChapterBinding::bind)

    private var bookUrl: String = ""
    private var onSelected: ((ReferenceItem) -> Unit)? = null
    private var chapters: List<BookChapter> = emptyList()
    private var filtered: MutableList<BookChapter> = mutableListOf()
    private var selectedIndex: Int = RecyclerView.NO_POSITION

    companion object {
        fun newInstance(bookUrl: String): ChapterPickerDialog {
            return ChapterPickerDialog().apply {
                arguments = Bundle().apply {
                    putString("bookUrl", bookUrl)
                }
            }
        }
    }

    fun setOnSelectedListener(listener: (ReferenceItem) -> Unit) {
        onSelected = listener
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        bookUrl = arguments?.getString("bookUrl") ?: ""
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        binding.rvList.layoutManager = LinearLayoutManager(requireContext())

        // 搜索过滤
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                filterChapters(s?.toString() ?: "")
            }
        })

        // 关闭
        binding.ivClose.setOnClickListener { dismiss() }
        binding.btnCancel.setOnClickListener { dismiss() }

        // 确认
        binding.btnConfirm.setOnClickListener {
            if (selectedIndex == RecyclerView.NO_POSITION) return@setOnClickListener
            val list = if (filtered.isNotEmpty()) filtered else chapters
            if (selectedIndex >= list.size) return@setOnClickListener
            val ch = list[selectedIndex]
            onSelected?.invoke(
                ReferenceItem(
                    type = "chapter",
                    title = ch.title,
                    bookUrl = bookUrl,
                    chapterIndex = ch.index
                )
            )
            dismiss()
        }

        // 加载数据
        loadChapters()
    }

    private fun loadChapters() {
        execute {
            val list = appDb.bookChapterDao.getChapterList(bookUrl)
            chapters = list
            filtered = list.toMutableList()
            binding.rvList.adapter = ChapterAdapter(filtered, selectedIndex) { pos ->
                selectedIndex = pos
                binding.rvList.adapter?.notifyDataSetChanged()
            }
        }
    }

    private fun filterChapters(query: String) {
        filtered = if (query.isBlank()) {
            chapters.toMutableList()
        } else {
            chapters.filter {
                it.title.contains(query, ignoreCase = true)
            }.toMutableList()
        }
        selectedIndex = RecyclerView.NO_POSITION
        binding.rvList.adapter = ChapterAdapter(filtered, selectedIndex) { pos ->
            selectedIndex = pos
            binding.rvList.adapter?.notifyDataSetChanged()
        }
    }
}

/**
 * 章节选择列表适配器
 */
class ChapterAdapter(
    private val items: List<BookChapter>,
    private val selectedPos: Int,
    private val onItemClick: (Int) -> Unit
) : RecyclerView.Adapter<ChapterAdapter.ViewHolder>() {

    inner class ViewHolder(val binding: ItemPickerChapterBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemPickerChapterBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val ch = items[position]
        val b = holder.binding
        b.tvTitle.text = ch.title
        b.tvSubtitle.text = "${ch.index + 1}"
        b.tvSubtitle.visibility = View.VISIBLE

        val isSelected = position == selectedPos
        b.ivChecked.setImageResource(
            if (isSelected) R.drawable.ic_check_circle else 0
        )
        b.ivChecked.visibility = if (isSelected) View.VISIBLE else View.INVISIBLE
        b.ivChecked.imageTintList =
            android.content.res.ColorStateList.valueOf(
                holder.itemView.context.getColor(R.color.accent)
            )

        holder.itemView.setOnClickListener { onItemClick(position) }
    }

    override fun getItemCount() = items.size
}
