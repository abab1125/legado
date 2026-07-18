package io.legado.app.ui.book.read.ai.liyuan

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import io.legado.app.base.BaseDialogFragment
import io.legado.app.databinding.DialogLiyuanChoiceBinding
import io.legado.app.R
import io.legado.app.utils.GSON

/**
 * 决策卡弹窗
 *
 * 当梨园 agent 在关键剧情节点停笔询问时，弹出此对话框让用户选择剧情走向。
 *
 * 触发时机：收到 {type:"choice"} 帧
 * 关闭时机：用户选择了选项 / 其他端先选了 / 超时
 *
 * 参数传递方式（legado 标准 pattern）：
 *    LiyuanChoiceDialog.newInstance(id, question, options, placeholder)
 *    dialog.onReply = { value, stop -> ... }
 *    dialog.show(supportFragmentManager, "liyuan_choice")
 */
class LiyuanChoiceDialog : BaseDialogFragment(R.layout.dialog_liyuan_choice) {

    /** 用户选择后的回调 */
    var onReply: ((value: String?, stop: Boolean) -> Unit)? = null

    private var _binding: DialogLiyuanChoiceBinding? = null
    private val binding get() = _binding!!

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        _binding = DialogLiyuanChoiceBinding.bind(view)

        val args = requireArguments()
        val question = args.getString("question", "")
        val optionsJson = args.getString("options", "[]")
        val placeholder = args.getString("placeholder", null)
        @Suppress("UNCHECKED_CAST")
        val options = GSON.fromJson(optionsJson, List::class.java) as? List<String> ?: emptyList()

        // 问题文本
        binding.tvQuestion.text = question

        // 选项列表
        binding.lvOptions.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_list_item_1,
            options
        )

        // 选项点击
        binding.lvOptions.setOnItemClickListener { _, _, position, _ ->
            val value = options.getOrNull(position)
            onReply?.invoke(value, false)
            dismissAllowingStateLoss()
        }

        // 自由输入框
        if (!placeholder.isNullOrBlank()) {
            binding.etFreeInput.visibility = View.VISIBLE
            binding.etFreeInput.hint = placeholder
        }

        // 确认按钮（自由输入）
        binding.btnConfirm.setOnClickListener {
            val text = binding.etFreeInput.text?.toString()?.trim()
            onReply?.invoke(text, false)
            dismissAllowingStateLoss()
        }

        // 跳过/停止
        binding.btnStop.setOnClickListener {
            onReply?.invoke(null, true)
            dismissAllowingStateLoss()
        }

        // 取消
        binding.btnCancel.setOnClickListener {
            dismissAllowingStateLoss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance(
            id: String,
            question: String,
            options: List<String>,
            placeholder: String?
        ): LiyuanChoiceDialog {
            return LiyuanChoiceDialog().apply {
                arguments = Bundle().apply {
                    putString("id", id)
                    putString("question", question)
                    putString("options", GSON.toJson(options))
                    if (placeholder != null) putString("placeholder", placeholder)
                }
            }
        }
    }
}
