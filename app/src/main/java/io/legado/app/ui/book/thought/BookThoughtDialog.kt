package io.legado.app.ui.book.thought

import android.graphics.PorterDuff
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookThought
import io.legado.app.databinding.DialogBookThoughtBinding
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.getPrimaryTextColor
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.dpToPx
import io.legado.app.utils.postEvent
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BookThoughtDialog() : BaseDialogFragment(R.layout.dialog_book_thought, true) {

    constructor(thought: BookThought, editPos: Int = -1) : this() {
        arguments = Bundle().apply {
            putInt("editPos", editPos)
            putParcelable("thought", thought)
        }
    }

    private val binding by viewBinding(DialogBookThoughtBinding::bind)

    override fun onStart() {
        super.onStart()
        dialog?.window?.run {
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setBackgroundDrawableResource(android.R.color.transparent)
            decorView.setPadding(0, 0, 0, 0)
            val attr = attributes
            attr.dimAmount = 0.0f
            attr.gravity = Gravity.BOTTOM
            attributes = attr
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        val bg = requireContext().bottomBackground
        val isLight = ColorUtils.isColorLight(bg)
        val textColor = requireContext().getPrimaryTextColor(isLight)
        binding.run {
            // 设置卡片背景色
            root.setBackgroundColor(bg)
            tvTitle.setTextColor(textColor)
            tvChapterName.setTextColor(textColor)
            editSelectedText.setTextColor(textColor)
            editThought.setTextColor(textColor)
            tvUnderlineStyle.setTextColor(requireContext().getColor(R.color.accent))
        }
        val args = arguments ?: let {
            dismiss()
            return
        }
        @Suppress("DEPRECATION")
        val bookThought = args.getParcelable<BookThought>("thought")
        bookThought ?: let {
            dismiss()
            return
        }
        val editPos = args.getInt("editPos", -1)
        binding.tvFooterLeft.visible(editPos >= 0)
        binding.run {
            tvChapterName.text = bookThought.chapterName
            editSelectedText.setText(bookThought.selectedText)
            editThought.setText(bookThought.thought)
            tvShare.setOnClickListener {
                val thoughtText = editThought.text?.toString()?.trim().orEmpty()
                if (thoughtText.isEmpty()) {
                    context?.toastOnUi(R.string.cannot_empty)
                    return@setOnClickListener
                }
                ShareThoughtDialog.newInstance(bookThought, thoughtText)
                    .show(childFragmentManager, "shareThoughtDialog")
            }
            tvOk.setOnClickListener {
                val thoughtText = editThought.text?.toString()?.trim().orEmpty()
                if (thoughtText.isEmpty()) {
                    context?.toastOnUi(R.string.cannot_empty)
                    return@setOnClickListener
                }
                lifecycleScope.launch {
                    withContext(IO) {
                        appDb.bookThoughtDao.insert(
                            bookThought.copy(
                                thought = thoughtText,
                                updateTime = System.currentTimeMillis()
                            )
                        )
                    }
                    postEvent(EventBus.REFRESH_BOOK_THOUGHT, true)
                    context?.toastOnUi(R.string.thought_saved)
                    dismiss()
                }
            }
            tvFooterLeft.setOnClickListener {
                lifecycleScope.launch {
                    withContext(IO) {
                        appDb.bookThoughtDao.delete(bookThought)
                    }
                    postEvent(EventBus.REFRESH_BOOK_THOUGHT, true)
                    context?.toastOnUi(R.string.thought_deleted)
                    dismiss()
                }
            }
            tvUnderlineStyle.setOnClickListener {
                // TODO: 弹出 ThoughtUnderlineStyleDialog（第三部分实现）
            }
        }
    }
}
