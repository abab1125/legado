package io.legado.app.ui.book.read.ai

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.appDb
import io.legado.app.data.entities.WritingPrompt
import io.legado.app.databinding.DialogPickerPromptBinding
import io.legado.app.databinding.ItemPickerPromptBinding
import io.legado.app.utils.viewbindingdelegate.viewBinding

/**
 * 提示词选择弹窗——在 AI 聊天中通过 @提示词 引用写作提示词
 */
class PromptPickerDialog : BaseDialogFragment(R.layout.dialog_picker_prompt) {

    private val binding by viewBinding(DialogPickerPromptBinding::bind)

    private var onSelected: ((ReferenceItem) -> Unit)? = null
    private var allItems: List<WritingPrompt> = emptyList()
    private var filtered: MutableList<WritingPrompt> = mutableListOf()
    private var selectedIndex: Int = RecyclerView.NO_POSITION
    private var activeType: String = ""

    companion object {
        fun newInstance(): PromptPickerDialog {
            return PromptPickerDialog()
        }
    }

    fun setOnSelectedListener(listener: (ReferenceItem) -> Unit) {
        onSelected = listener
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        binding.rvList.layoutManager = LinearLayoutManager(requireContext())

        // 搜索过滤
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                applyFilters(s?.toString() ?: "", activeType)
            }
        })

        // 关闭
        binding.ivClose.setOnClickListener { dismiss() }
        binding.btnCancel.setOnClickListener { dismiss() }

        // 确认
        binding.btnConfirm.setOnClickListener {
            if (selectedIndex == RecyclerView.NO_POSITION) return@setOnClickListener
            val list = if (filtered.isNotEmpty()) filtered else allItems
            if (selectedIndex >= list.size) return@setOnClickListener
            val wp = list[selectedIndex]
            onSelected?.invoke(
                ReferenceItem(
                    type = "prompt",
                    title = wp.title,
                    id = wp.id
                )
            )
            dismiss()
        }

        // 加载数据
        loadPrompts()
    }

    private fun loadPrompts() {
        execute {
            allItems = appDb.writingPromptDao.all
            filtered = allItems.toMutableList()

            // 构建分类筛选 pills
            buildTypePills()

            binding.rvList.adapter = PromptAdapter(filtered, selectedIndex) { pos ->
                selectedIndex = pos
                binding.rvList.adapter?.notifyDataSetChanged()
            }
        }
    }

    private fun buildTypePills() {
        val types = allItems.map { it.type }.distinct().sorted()
        val context = requireContext()
        val layout = binding.layoutPills
        layout.removeAllViews()

        // "全部" pill
        val allPill = createPillView(context, context.getString(R.string.ref_filter_all), true) {
            activeType = ""
            applyFilters(binding.etSearch.text?.toString() ?: "", activeType)
            refreshPillStates()
        }
        layout.addView(allPill)

        // 各类型 pill
        for (type in types) {
            val label = getTypeLabel(type)
            val pill = createPillView(context, label, false) {
                activeType = type
                applyFilters(binding.etSearch.text?.toString() ?: "", activeType)
                refreshPillStates()
            }
            layout.addView(pill)
        }
    }

    private fun createPillView(
        context: android.content.Context,
        label: String,
        isActive: Boolean,
        onClick: () -> Unit
    ): TextView {
        val pill = TextView(context)
        pill.text = label
        pill.textSize = 12f
        pill.setPadding(12, 5, 12, 5)
        val lp = ViewGroup.MarginLayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.marginEnd = 8
        pill.layoutParams = lp
        pill.gravity = View.TEXT_ALIGNMENT_CENTER
        pill.isClickable = true
        pill.isFocusable = true
        pill.tag = label

        updatePillStyle(pill, isActive)
        pill.setOnClickListener { onClick() }
        return pill
    }

    private fun refreshPillStates() {
        for (i in 0 until binding.layoutPills.childCount) {
            val child = binding.layoutPills.getChildAt(i) as? TextView ?: continue
            val label = child.tag as? String ?: continue
            val isActive = when {
                label == getString(R.string.ref_filter_all) && activeType.isEmpty() -> true
                label != getString(R.string.ref_filter_all) && getTypeKey(label) == activeType -> true
                else -> false
            }
            updatePillStyle(child, isActive)
        }
    }

    private fun updatePillStyle(pill: TextView, isActive: Boolean) {
        val context = pill.context
        if (isActive) {
            pill.setTextColor(ContextCompat.getColor(context, R.color.white))
            pill.setBackgroundResource(R.drawable.bg_filter_active)
        } else {
            pill.setTextColor(ContextCompat.getColor(context, R.color.secondaryText))
            pill.setBackgroundResource(R.drawable.bg_filter_inactive)
        }
    }

    private fun getTypeLabel(type: String): String {
        return when (type) {
            "character" -> getString(R.string.ref_filter_character)
            "world" -> getString(R.string.ref_filter_world)
            "style" -> getString(R.string.ref_filter_style)
            "outline" -> getString(R.string.ref_filter_outline)
            "other" -> getString(R.string.ref_filter_other)
            else -> type
        }
    }

    private fun getTypeKey(label: String): String {
        return when (label) {
            getString(R.string.ref_filter_character) -> "character"
            getString(R.string.ref_filter_world) -> "world"
            getString(R.string.ref_filter_style) -> "style"
            getString(R.string.ref_filter_outline) -> "outline"
            getString(R.string.ref_filter_other) -> "other"
            else -> label
        }
    }

    private fun applyFilters(query: String, type: String) {
        filtered = allItems.filter { wp ->
            val matchQuery = query.isBlank() ||
                wp.title.contains(query, ignoreCase = true) ||
                wp.content.contains(query, ignoreCase = true)
            val matchType = type.isBlank() || wp.type == type
            matchQuery && matchType
        }.toMutableList()
        selectedIndex = RecyclerView.NO_POSITION
        binding.rvList.adapter = PromptAdapter(filtered, selectedIndex) { pos ->
            selectedIndex = pos
            binding.rvList.adapter?.notifyDataSetChanged()
        }
    }
}

/**
 * 提示词选择列表适配器
 */
class PromptAdapter(
    private val items: List<WritingPrompt>,
    private val selectedPos: Int,
    private val onItemClick: (Int) -> Unit
) : RecyclerView.Adapter<PromptAdapter.ViewHolder>() {

    inner class ViewHolder(val binding: ItemPickerPromptBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemPickerPromptBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val wp = items[position]
        val b = holder.binding
        b.tvTitle.text = wp.title
        b.tvSubtitle.text = wp.content.take(80)
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

        val ctx = holder.itemView.context
        if (isSelected) {
            val accent = ctx.getColor(R.color.accent)
            holder.itemView.setBackgroundColor(
                android.graphics.Color.argb(60,
                    android.graphics.Color.red(accent),
                    android.graphics.Color.green(accent),
                    android.graphics.Color.blue(accent))
            )
        } else {
            val outValue = android.util.TypedValue()
            ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            holder.itemView.setBackgroundResource(outValue.resourceId)
        }

        holder.itemView.setOnClickListener { onItemClick(position) }
    }

    override fun getItemCount() = items.size
}
