package io.legado.app.ui.main

import android.os.Bundle
import android.view.View
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
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class KnowledgeManageFragment() : BaseFragment(R.layout.fragment_knowledge_manage),
    MainFragmentInterface {

    constructor(position: Int) : this() {
        arguments = Bundle().apply { putInt("position", position) }
    }

    override val position: Int? get() = arguments?.getInt("position")

    private val binding by viewBinding(FragmentKnowledgeManageBinding::bind)

    private val adapter by lazy {
        KnowledgeManageAdapter(requireContext(), onItemClick = { showEditDialog(it) })
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) = binding.run {
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.addItemDecoration(VerticalDivider(requireContext()))
        recyclerView.adapter = adapter
        recyclerView.applyNavigationBarPadding()

        titleBar.setAddButton { showEditDialog(null) }
    }

    override fun onResume() {
        super.onResume()
        loadData()
    }

    private fun loadData() = lifecycleScope.launch {
        val points = withContext(IO) {
            appDb.knowledgePointDao.all
        }
        adapter.setItems(points)
    }

    private fun showEditDialog(point: KnowledgePoint?) {
        val dialog = KnowledgeEditDialog.newInstance(point)
        dialog.setOnDismissListener { loadData() }
        dialog.show(parentFragmentManager, "knowledge_edit")
    }
}
