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
import io.legado.app.data.entities.KnowledgePoint
import io.legado.app.databinding.DialogKnowledgeManageBinding
import io.legado.app.databinding.ItemPromptBinding
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class KnowledgeManageDialog : BaseDialogFragment(R.layout.dialog_knowledge_manage) {

    private val binding by viewBinding(DialogKnowledgeManageBinding::bind)
    private val adapter by lazy { KnowledgeAdapter() }
    private var items = mutableListOf<KnowledgePoint>()

    private val bookUrl: String
        get() = arguments?.getString(ARG_BOOK_URL) ?: ""

    companion object {
        private const val ARG_BOOK_URL = "bookUrl"
        fun newInstance(bookUrl: String) = KnowledgeManageDialog().apply {
            arguments = Bundle().apply { putString(ARG_BOOK_URL, bookUrl) }
        }
    }

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, 0.8f)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.recycler.adapter = adapter
        binding.btnClose.setOnClickListener { dismiss() }
        binding.btnAdd.setOnClickListener { showAddDialog() }
        loadItems()
    }

    private fun loadItems() {
        if (bookUrl.isBlank()) return
        lifecycleScope.launch(Dispatchers.IO) {
            val list = appDb.knowledgePointDao.getByBook(bookUrl)
            withContext(Dispatchers.Main) {
                items = list.toMutableList()
                adapter.setItems(items.toList())
            }
        }
    }

    private fun showAddDialog() {
        val titleInput = TextInputEditText(requireContext()).apply { hint = "知识点标题" }
        val contentInput = TextInputEditText(requireContext()).apply { hint = "内容" }
        val container = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(titleInput)
            addView(contentInput)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("添加知识点")
            .setView(container)
            .setPositiveButton("添加") { _, _ ->
                val title = titleInput.text?.toString()?.trim()
                val content = contentInput.text?.toString()?.trim()
                if (title.isNullOrBlank() || content.isNullOrBlank()) return@setPositiveButton
                lifecycleScope.launch(Dispatchers.IO) {
                    val kp = KnowledgePoint(
                        bookUrl = bookUrl,
                        title = title,
                        content = content,
                        tags = "custom"
                    )
                    appDb.knowledgePointDao.insert(kp)
                    loadItems()
                    withContext(Dispatchers.Main) { toastOnUi("已添加") }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    inner class KnowledgeAdapter :
        RecyclerAdapter<KnowledgePoint, ItemPromptBinding>(requireActivity()) {

        override fun getViewBinding(parent: ViewGroup): ItemPromptBinding {
            return ItemPromptBinding.inflate(inflater, parent, false)
        }

        override fun registerListener(holder: ItemViewHolder, binding: ItemPromptBinding) {
            binding.root.setOnLongClickListener {
                getItemByLayoutPosition(holder.layoutPosition)?.let { kp ->
                    showEditDeleteDialog(kp)
                }
                true
            }
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: ItemPromptBinding,
            item: KnowledgePoint,
            payloads: MutableList<Any>
        ) {
            binding.tvTitle.text = item.title
            binding.tvContent.text = item.content
            binding.tvMeta.text = item.tags ?: ""
        }
    }

    private fun showEditDeleteDialog(kp: KnowledgePoint) {
        val titleInput = TextInputEditText(requireContext()).apply { setText(kp.title) }
        val contentInput = TextInputEditText(requireContext()).apply { setText(kp.content) }
        val container = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(titleInput)
            addView(contentInput)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("编辑 / 删除知识点")
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                val newTitle = titleInput.text?.toString()?.trim()
                val newContent = contentInput.text?.toString()?.trim()
                if (newTitle.isNullOrBlank() || newContent.isNullOrBlank()) return@setPositiveButton
                lifecycleScope.launch(Dispatchers.IO) {
                    kp.title = newTitle
                    kp.content = newContent
                    appDb.knowledgePointDao.update(kp)
                    loadItems()
                    withContext(Dispatchers.Main) { toastOnUi("已更新") }
                }
            }
            .setNeutralButton("删除") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    appDb.knowledgePointDao.delete(kp)
                    loadItems()
                    withContext(Dispatchers.Main) { toastOnUi("已删除") }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
