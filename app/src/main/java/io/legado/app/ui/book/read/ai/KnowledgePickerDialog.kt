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
import io.legado.app.data.entities.KnowledgePoint
import io.legado.app.databinding.DialogPickerKnowledgeBinding
import io.legado.app.databinding.ItemPickerKnowledgeBinding
import io.legado.app.utils.viewbindingdelegate.viewBinding

/**
 * 知识点选择弹窗——在 AI 聊天中通过 @知识点 引用知识点
 * 支持分类 pill 筛选，角色卡显示小说名前缀
 */
class KnowledgePickerDialog : BaseDialogFragment(R.layout.dialog_picker_knowledge) {

    private val binding by viewBinding(DialogPickerKnowledgeBinding::bind)

    private var onSelected: ((ReferenceItem) -> Unit)? = null
    private var allItems: List<KnowledgePoint> = emptyList()
    private var filtered: MutableList<KnowledgePoint> = mutableListOf()
    private var selectedIndex: Int = RecyclerView.NO_POSITION
    private var activeCategory: String = ""

    companion object {
        fun newInstance(): KnowledgePickerDialog {
            return KnowledgePickerDialog()
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
                applyFilters(s?.toString() ?: "", activeCategory)
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
            val kp = list[selectedIndex]
            // 角色卡引用时，title 带上小说名前缀
            val title = if (kp.subCategory == "novel-character" && kp.novelName.isNotBlank()) {
                "${kp.novelName}/${kp.title}"
            } else {
                kp.title
            }
            onSelected?.invoke(
                ReferenceItem(
                    type = "knowledge",
                    title = title,
                    id = kp.id
                )
            )
            dismiss()
        }

        // 加载数据
        loadKnowledge()
    }

    private fun loadKnowledge() {
        execute {
            allItems = appDb.knowledgePointDao.all
            filtered = allItems.toMutableList()

            // 构建分类筛选 pills
            buildCategoryPills()

            binding.rvList.adapter = KnowledgeAdapter(filtered, selectedIndex) { pos ->
                selectedIndex = pos
                binding.rvList.adapter?.notifyDataSetChanged()
            }
        }
    }

    private fun buildCategoryPills() {
        // 收集所有存在的分类
        val categories = allItems.map { it.category }.distinct().sorted()
        val context = requireContext()
        val layout = binding.layoutPills
        layout.removeAllViews()

        // "全部" pill
        val allPill = createPillView(context, context.getString(R.string.ref_filter_all), true) {
            activeCategory = ""
            applyFilters(binding.etSearch.text?.toString() ?: "", activeCategory)
            refreshPillStates()
        }
        layout.addView(allPill)

        // 各分类 pill
        for (cat in categories) {
            val label = getCategoryLabel(cat)
            val pill = createPillView(context, label, false) {
                activeCategory = cat
                applyFilters(binding.etSearch.text?.toString() ?: "", activeCategory)
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
                label == getString(R.string.ref_filter_all) && activeCategory.isEmpty() -> true
                label != getString(R.string.ref_filter_all) && getCategoryKey(label) == activeCategory -> true
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

    private fun getCategoryLabel(category: String): String = when (category) {
        "character" -> getString(R.string.ref_filter_character)
        "place" -> getString(R.string.ref_filter_place)
        "event" -> getString(R.string.ref_filter_event)
        "note" -> getString(R.string.ref_filter_note)
        "other" -> getString(R.string.ref_filter_other)
        else -> category
    }

    private fun getCategoryKey(label: String): String = when (label) {
        getString(R.string.ref_filter_character) -> "character"
        getString(R.string.ref_filter_place) -> "place"
        getString(R.string.ref_filter_event) -> "event"
        getString(R.string.ref_filter_note) -> "note"
        getString(R.string.ref_filter_other) -> "other"
        else -> label
    }

    private fun applyFilters(query: String, category: String) {
        filtered = allItems.filter { kp ->
            val matchQuery = query.isBlank() ||
                kp.title.contains(query, ignoreCase = true) ||
                kp.content.contains(query, ignoreCase = true)
            val matchCategory = category.isBlank() || kp.category == category
            matchQuery && matchCategory
        }.toMutableList()
        selectedIndex = RecyclerView.NO_POSITION
        binding.rvList.adapter = KnowledgeAdapter(filtered, selectedIndex) { pos ->
            selectedIndex = pos
            binding.rvList.adapter?.notifyDataSetChanged()
        }
    }
}

/**
 * 知识点选择列表适配器
 */
class KnowledgeAdapter(
    private val items: List<KnowledgePoint>,
    private val selectedPos: Int,
    private val onItemClick: (Int) -> Unit
) : RecyclerView.Adapter<KnowledgeAdapter.ViewHolder>() {

    inner class ViewHolder(val binding: ItemPickerKnowledgeBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemPickerKnowledgeBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val kp = items[position]
        val b = holder.binding
        b.tvTitle.text = kp.title
        // 小说角色子标题显示小说名
        val subtitle = if (kp.subCategory == "novel-character" && kp.novelName.isNotBlank()) {
            "📖 ${kp.novelName} · ${kp.content.take(60)}"
        } else {
            kp.content.take(80)
        }
        b.tvSubtitle.text = subtitle
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
