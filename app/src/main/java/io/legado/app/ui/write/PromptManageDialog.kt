package io.legado.app.ui.write

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.appDb
import io.legado.app.data.entities.WritingPrompt
import io.legado.app.databinding.DialogPromptManageBinding
import io.legado.app.databinding.ItemPromptBinding
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 全局提示词管理对话框
 * 完全全局化，不绑定任何书籍
 */
class PromptManageDialog : BaseDialogFragment(R.layout.dialog_prompt_manage) {

    private val binding by viewBinding(DialogPromptManageBinding::bind)
    private val adapter by lazy { PromptAdapter() }
    private var prompts = mutableListOf<WritingPrompt>()

    companion object {
        fun newInstance() = PromptManageDialog()
    }

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, 0.8f)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.recycler.adapter = adapter
        binding.titleBar.setNavigationOnClickListener { dismiss() }
        binding.titleBar.menu.add("添加").apply {
            setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        binding.titleBar.toolbar.setOnMenuItemClickListener { item ->
            when (item.title.toString()) {
                "添加" -> { showAddDialog(); true }
                else -> false
            }
        }
        loadPrompts()
    }

    private fun loadPrompts() {
        lifecycleScope.launch(Dispatchers.IO) {
            val list = appDb.writingPromptDao.all.sortedBy { it.sortOrder }
            withContext(Dispatchers.Main) {
                prompts = list.toMutableList()
                adapter.setItems(prompts.toList())
            }
        }
    }

    private fun showAddDialog() {
        val editText = TextInputEditText(requireContext()).apply { hint = "提示词内容" }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("添加提示词")
            .setView(editText)
            .setPositiveButton("添加") { _, _ ->
                val text = editText.text?.toString()?.trim()
                if (text.isNullOrBlank()) return@setPositiveButton
                lifecycleScope.launch(Dispatchers.IO) {
                    val prompt = WritingPrompt(
                        title = "提示词 ${prompts.size + 1}",
                        content = text,
                        type = "custom",
                        sortOrder = (prompts.maxOfOrNull { it.sortOrder } ?: 0) + 1
                    )
                    appDb.writingPromptDao.insert(prompt)
                    loadPrompts()
                    withContext(Dispatchers.Main) { toastOnUi("已添加") }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    inner class PromptAdapter :
        RecyclerAdapter<WritingPrompt, ItemPromptBinding>(requireActivity()) {

        override fun getViewBinding(parent: ViewGroup): ItemPromptBinding {
            return ItemPromptBinding.inflate(inflater, parent, false)
        }

        override fun registerListener(holder: ItemViewHolder, binding: ItemPromptBinding) {
            binding.root.setOnLongClickListener {
                getItemByLayoutPosition(holder.layoutPosition)?.let { prompt ->
                    showEditDeleteDialog(prompt)
                }
                true
            }
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: ItemPromptBinding,
            item: WritingPrompt,
            payloads: MutableList<Any>
        ) {
            binding.tvTitle.text = item.title
            binding.tvContent.text = item.content
        }
    }

    private fun showEditDeleteDialog(prompt: WritingPrompt) {
        val editText = TextInputEditText(requireContext()).apply {
            setText(prompt.content)
            selectAll()
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("编辑 / 删除提示词")
            .setView(editText)
            .setPositiveButton("保存") { _, _ ->
                val newContent = editText.text?.toString()?.trim()
                if (newContent.isNullOrBlank()) return@setPositiveButton
                lifecycleScope.launch(Dispatchers.IO) {
                    val updated = prompt.copy(content = newContent.trim())
                    appDb.writingPromptDao.update(updated)
                    loadPrompts()
                    withContext(Dispatchers.Main) { toastOnUi("已更新") }
                }
            }
            .setNeutralButton("删除") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    appDb.writingPromptDao.delete(prompt)
                    loadPrompts()
                    withContext(Dispatchers.Main) { toastOnUi("已删除") }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
