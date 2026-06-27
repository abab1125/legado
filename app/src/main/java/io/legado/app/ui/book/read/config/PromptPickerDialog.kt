package io.legado.app.ui.book.read.config

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.appDb
import io.legado.app.data.entities.WritingPrompt
import io.legado.app.databinding.DialogPromptPickerBinding
import io.legado.app.databinding.ItemPromptPickerBinding
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PromptPickerDialog : BaseDialogFragment(R.layout.dialog_prompt_picker) {

    private val binding by viewBinding(DialogPromptPickerBinding::bind)
    private val adapter by lazy { PromptAdapter() }
    private var allPrompts = listOf<WritingPrompt>()
    private var currentFilter: String? = null // null = all
    private var currentKeyword = ""

    var onPromptSelected: ((WritingPrompt) -> Unit)? = null

    companion object {
        fun newInstance() = PromptPickerDialog()
    }

    // ponytail: tag 颜色映射用数据表代替 32 行 when
    private val typeColors = mapOf(
        "character" to Pair(R.drawable.bg_filter_active, R.color.md_blue_500),
        "world" to Pair(R.drawable.bg_filter_inactive, R.color.secondaryText),
        "style" to Pair(R.drawable.bg_filter_active, R.color.md_blue_500),
        "outline" to Pair(R.drawable.bg_filter_inactive, R.color.secondaryText)
    )

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.rvPrompts.layoutManager = LinearLayoutManager(requireContext())
        binding.rvPrompts.adapter = adapter

        binding.ivClose.setOnClickListener { dismiss() }
        binding.btnSave.setOnClickListener {
            adapter.getCheckedItem()?.let { prompt ->
                onPromptSelected?.invoke(prompt)
                dismiss()
            } ?: toastOnUi("请选择一个提示词")
        }

        setupFilters()
        setupSearch()
        loadPrompts()
    }

    private fun setupFilters() {
        val filterViews = listOf(
            binding.filterAll to null,
            binding.filterCharacter to "character",
            binding.filterWorld to "world",
            binding.filterStyle to "style",
            binding.filterOutline to "outline"
        )
        for ((view, type) in filterViews) {
            view.setOnClickListener {
                currentFilter = type
                updateFilterStyles()
                applyFilterAndSearch()
            }
        }
    }

    private fun updateFilterStyles() {
        val filters = listOf(
            binding.filterAll to null,
            binding.filterCharacter to "character",
            binding.filterWorld to "world",
            binding.filterStyle to "style",
            binding.filterOutline to "outline"
        )
        for ((view, type) in filters) {
            val isActive = currentFilter == type
            view.setBackgroundResource(
                if (isActive) R.drawable.bg_filter_active
                else R.drawable.bg_filter_inactive
            )
            view.setTextColor(
                if (isActive) requireContext().getColor(R.color.md_blue_500)
                else requireContext().getColor(R.color.secondaryText)
            )
        }
    }

    // ponytail: 用 KTX addTextChangedListener 代替手写 TextWatcher 三方法
    private fun setupSearch() {
        binding.etSearch.addTextChangedListener(afterTextChanged = { editable ->
            currentKeyword = editable?.toString()?.trim() ?: ""
            applyFilterAndSearch()
        })
    }

    private fun loadPrompts() {
        lifecycleScope.launch(Dispatchers.IO) {
            val prompts = appDb.writingPromptDao.all
            withContext(Dispatchers.Main) {
                allPrompts = prompts
                applyFilterAndSearch()
            }
        }
    }

    private fun applyFilterAndSearch() {
        val filtered = allPrompts.filter { prompt ->
            val matchesType = currentFilter == null || prompt.type == currentFilter
            val matchesKeyword = currentKeyword.isEmpty() ||
                    prompt.title.contains(currentKeyword, ignoreCase = true) ||
                    prompt.content.contains(currentKeyword, ignoreCase = true)
            matchesType && matchesKeyword
        }
        adapter.setItems(filtered)
    }

    inner class PromptAdapter :
        RecyclerAdapter<WritingPrompt, ItemPromptPickerBinding>(requireActivity()) {

        private var checkedPosition = -1

        fun getCheckedItem(): WritingPrompt? =
            if (checkedPosition in items.indices) items[checkedPosition] else null

        override fun getViewBinding(parent: ViewGroup): ItemPromptPickerBinding {
            return ItemPromptPickerBinding.inflate(inflater, parent, false)
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: ItemPromptPickerBinding,
            item: WritingPrompt,
            payloads: MutableList<Any>
        ) {
            binding.tvTitle.text = item.title
            binding.tvDesc.text = item.content

            val isChecked = holder.layoutPosition == checkedPosition
            binding.root.setBackgroundResource(
                if (isChecked) R.drawable.bg_card_selected
                else R.drawable.bg_card_white
            )

            // ponytail: tag 颜色查表，6 行代替 32 行 when
            binding.layoutTags.removeAllViews()
            val typeKey = item.type
            if (typeKey.isNotBlank() && typeKey != "other") {
                typeColors[typeKey]?.let { (bgRes, colorRes) ->
                    binding.layoutTags.addView(createTagView(typeKey, bgRes, colorRes))
                }
            }
        }

        override fun registerListener(holder: ItemViewHolder, binding: ItemPromptPickerBinding) {
            binding.root.setOnClickListener {
                val prev = checkedPosition
                checkedPosition = holder.layoutPosition
                if (prev >= 0 && prev < itemCount) notifyItemChanged(prev)
                if (checkedPosition >= 0 && checkedPosition < itemCount) notifyItemChanged(checkedPosition)
            }
        }

        private fun createTagView(type: String, bgRes: Int, colorRes: Int): View {
            return TextView(requireContext()).apply {
                text = when (type) {
                    "character" -> "角色"
                    "world" -> "世界观"
                    "style" -> "风格"
                    "outline" -> "大纲"
                    else -> type
                }
                textSize = 10f
                setPadding(6, 2, 6, 2)
                setBackgroundResource(bgRes)
                setTextColor(requireContext().getColor(colorRes))
                layoutParams = ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = 4 }
            }
        }
    }
}
