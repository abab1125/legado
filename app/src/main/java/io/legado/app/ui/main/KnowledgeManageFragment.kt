package io.legado.app.ui.main

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.BaseFragment
import io.legado.app.data.appDb
import io.legado.app.data.entities.KnowledgePoint
import io.legado.app.databinding.FragmentKnowledgeManageBinding
import io.legado.app.ui.widget.recycler.VerticalDivider
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.setAddButton
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 知识点管理页（三级钻取）
 *
 * 一级：分类列表（人物/地点/事件/笔记/其他）
 * 二级（仅人物）：小说名分组 + 经典角色（subCategory 为空）
 * 三级（仅人物·具体小说名）：某小说下的角色卡
 */
class KnowledgeManageFragment() : BaseFragment(R.layout.fragment_knowledge_manage),
    MainFragmentInterface {

    constructor(position: Int) : this() {
        arguments = Bundle().apply { putInt("position", position) }
    }

    override val position: Int? get() = arguments?.getInt("position")

    private val binding by viewBinding(FragmentKnowledgeManageBinding::bind)

    /** 全部知识点 */
    private var allPoints: List<KnowledgePoint> = emptyList()
    /** 当前显示的扁平数据（供 adapter 使用） */
    private var displayItems: MutableList<DisplayItem> = mutableListOf()
    /** 已展开的小说名集合 */
    private val expandedNovels = mutableSetOf<String>()

    private lateinit var adapter: KnowledgeManageAdapter

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) = binding.run {
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.addItemDecoration(VerticalDivider(requireContext()))
        recyclerView.applyNavigationBarPadding()

        titleBar.setAddButton { showEditDialog(null) }

        adapter = KnowledgeManageAdapter(requireContext(),
            onItemClick = { item -> when (item) {
                is DisplayItem.Category -> toggleCategory(item.category)
                is DisplayItem.NovelGroup -> toggleNovel(item.novelName)
                is DisplayItem.Knowledge -> showEditDialog(item.point)
            }},
            onCollapseCategory = { collapseAll() }
        )
        recyclerView.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        loadData()
    }

    private fun loadData() = lifecycleScope.launch {
        val points = withContext(IO) {
            appDb.knowledgePointDao.all
        }
        allPoints = points
        // 保持当前展开层级，只刷新数量
        rebuildCategoryLevel()
    }

    /** 构建一级视图：所有分类 */
    private fun rebuildCategoryLevel() {
        displayItems.clear()
        expandedNovels.clear()
        // 统计各分类数量
        val catCounts = mutableMapOf<String, Int>()
        for (p in allPoints) {
            val key = if (p.category == "character" && p.subCategory == "novel-character") "character" else p.category
            catCounts[key] = (catCounts[key] ?: 0) + 1
        }
        for (cat in CATEGORIES) {
            val count = catCounts[cat.first] ?: 0
            displayItems.add(DisplayItem.Category(cat.first, cat.second, count))
        }
        adapter.setItems(displayItems, showCollapse = false)
    }

    /** 展开某分类 —— 仅 character 支持二级展开 */
    private fun toggleCategory(category: String) {
        if (category != "character") {
            toastOnUi("仅「人物」分类支持展开查看子类目")
            return
        }
        val catIndex = displayItems.indexOfFirst { it is DisplayItem.Category && it.category == category }
        if (catIndex < 0) return

        // 移除当前分类之后的所有子项
        displayItems = displayItems.take(catIndex + 1).toMutableList()
        expandedNovels.clear()

        // 查出该分类下所有数据
        val charItems = allPoints.filter { it.category == "character" }

        // 拆分：经典角色（无subCategory）和小说角色（有subCategory）
        val classicRoles = charItems.filter { it.subCategory.isNullOrEmpty() }
        val novelRoles = charItems.filter { it.subCategory == "novel-character" && it.novelName.isNotBlank() }

        // 收集小说名（去重有序）
        val novelNames = novelRoles.map { it.novelName }.distinct()

        // 先加经典角色
        if (classicRoles.isNotEmpty()) {
            displayItems.add(DisplayItem.NovelGroup("", classicRoles.size, classicRoles))
            for (role in classicRoles) {
                displayItems.add(DisplayItem.Knowledge(role))
            }
        }

        // 再加小说名分组（默认收起）
        for (name in novelNames) {
            val items = novelRoles.filter { it.novelName == name }
            displayItems.add(DisplayItem.NovelGroup(name, items.size, items))
        }
        adapter.setItems(displayItems, showCollapse = true)
    }

    /** 展开/收起小说名下的角色 */
    private fun toggleNovel(novelName: String) {
        val groupIndex = displayItems.indexOfFirst { it is DisplayItem.NovelGroup && it.novelName == novelName }
        if (groupIndex < 0) return

        if (expandedNovels.contains(novelName)) {
            // 收起：移除该 group 之后的子项直到下一个 group 或分类
            expandedNovels.remove(novelName)
            var end = groupIndex + 1
            while (end < displayItems.size && displayItems[end] !is DisplayItem.Category && displayItems[end] !is DisplayItem.NovelGroup) {
                end++
            }
            displayItems = (displayItems.take(groupIndex + 1) + displayItems.drop(end)).toMutableList()
        } else {
            // 展开：插入子项
            expandedNovels.add(novelName)
            val group = displayItems[groupIndex] as DisplayItem.NovelGroup
            val insertIdx = groupIndex + 1
            displayItems.addAll(insertIdx, group.children.map { DisplayItem.Knowledge(it) })
        }
        adapter.setItems(displayItems, showCollapse = true)
    }

    private fun collapseAll() {
        rebuildCategoryLevel()
    }

    private fun showEditDialog(point: KnowledgePoint?) {
        val dialog = KnowledgeEditDialog.newInstance(point)
        dialog.setOnDismissListener { loadData() }
        dialog.show(parentFragmentManager, "knowledge_edit")
    }

    companion object {
        /** 一级分类：pair of (category key, 中文名) */
        val CATEGORIES = listOf(
            "character" to "人物",
            "place" to "地点",
            "event" to "事件",
            "note" to "笔记",
            "other" to "其他"
        )
    }
}

/** 显示层级节点 */
sealed class DisplayItem {
    data class Category(val category: String, val label: String, val count: Int) : DisplayItem()
    data class NovelGroup(val novelName: String, val childCount: Int, val children: List<KnowledgePoint> = emptyList()) : DisplayItem()
    data class Knowledge(val point: KnowledgePoint) : DisplayItem()
}
