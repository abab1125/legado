package io.legado.app.ui.main

import android.os.Bundle
import android.view.View
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.BaseFragment
import io.legado.app.data.appDb
import io.legado.app.data.entities.WritingPrompt
import io.legado.app.databinding.FragmentPromptManageBinding
import io.legado.app.ui.widget.recycler.VerticalDivider
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PromptManageFragment() : BaseFragment(R.layout.fragment_prompt_manage),
    MainFragmentInterface {

    constructor(position: Int) : this() {
        arguments = Bundle().apply { putInt("position", position) }
    }

    override val position: Int? get() = arguments?.getInt("position")

    private val binding by viewBinding(FragmentPromptManageBinding::bind)

    private val adapter by lazy {
        PromptManageAdapter(requireContext(),
            onItemClick = { showEditDialog(it) },
            onDeleteClick = { deletePrompt(it) }
        )
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) = binding.run {
        tvTitle.setText(R.string.prompt)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.addItemDecoration(VerticalDivider(requireContext()))
        recyclerView.adapter = adapter
        recyclerView.applyNavigationBarPadding()
        ivAdd.setOnClickListener { showEditDialog(null) }
    }

    override fun onResume() {
        super.onResume()
        loadData()
    }

    private fun loadData() = lifecycleScope.launch {
        val prompts = withContext(IO) {
            appDb.writingPromptDao.all
        }
        adapter.setItems(prompts)
    }

    private fun showEditDialog(prompt: WritingPrompt?) {
        val dialog = PromptEditDialog.newInstance(prompt)
        dialog.setOnDismissListener { loadData() }
        dialog.show(parentFragmentManager, "prompt_edit")
    }

    private fun deletePrompt(prompt: WritingPrompt) {
        lifecycleScope.launch {
            withContext(IO) {
                appDb.writingPromptDao.delete(prompt)
            }
            loadData()
            toastOnUi("已删除")
        }
    }
}
