package io.legado.app.ui.book.read.ai.liyuan

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import io.legado.app.base.BaseDialogFragment
import io.legado.app.databinding.DialogLiyuanChoiceBinding
import io.legado.app.R

/**
 * 决策卡弹窗
 *
 * 当梨园 agent 在关键剧情节点停笔询问时，弹出此对话框让用户选择剧情走向。
 *
 * 触发时机：收到 {type:"choice"} 帧
 * 关闭时机：用户选择了选项 / 其他端先选了 / 超时
 */
class LiyuanChoiceDialog(
    private val choiceId: String,
    private val question: String,
    private val options: List<String>,
    private val placeholder: String?,
    private val onReply: (value: String?, stop: Boolean) -> Unit
) : BaseDialogFragment(R.layout.dialog_liyuan_choice) {

    private var _binding: DialogLiyuanChoiceBinding? = null
    private val binding get() = _binding!!

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        _binding = DialogLiyuanChoiceBinding.bind(view)

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
            onReply(value, false)
            dismissAllowingStateLoss()
        }

        // 自由输入框
        if (placeholder != null) {
            binding.etFreeInput.visibility = View.VISIBLE
            binding.etFreeInput.hint = placeholder
        }

        // 确认按钮（自由输入）
        binding.btnConfirm.setOnClickListener {
            val text = binding.etFreeInput.text?.toString()?.trim()
            onReply(text, false)
            dismissAllowingStateLoss()
        }

        // 跳过/停止
        binding.btnStop.setOnClickListener {
            onReply(null, true)
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
}
