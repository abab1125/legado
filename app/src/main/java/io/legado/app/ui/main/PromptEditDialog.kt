package io.legado.app.ui.main

import android.os.Bundle
import android.view.View
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.appDb
import io.legado.app.data.entities.WritingPrompt
import io.legado.app.databinding.DialogPromptEditBinding
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.GSON
import io.legado.app.utils.setLayout
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PromptEditDialog : BaseDialogFragment(R.layout.dialog_prompt_edit) {

    private val binding by viewBinding(DialogPromptEditBinding::bind)
    private var originalPrompt: WritingPrompt? = null

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, 0.85f)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) = binding.run {
        val promptJson = arguments?.getString("prompt")
        if (promptJson != null) {
            originalPrompt = GSON.fromJson(promptJson, WritingPrompt::class.java)
            originalPrompt?.let { p ->
                etTitle.setText(p.title)
                etContent.setText(p.content)
                when (p.type) {
                    "character" -> rbCharacter.isChecked = true
                    "world" -> rbWorld.isChecked = true
                    "style" -> rbStyle.isChecked = true
                    "outline" -> rbOutline.isChecked = true
                    else -> rbOther.isChecked = true
                }
            }
        }

        btnSave.setOnClickListener {
            val title = etTitle.text.toString().trim()
            val content = etContent.text.toString().trim()
            if (title.isEmpty()) {
                toastOnUi("标题不能为空")
                return@setOnClickListener
            }
            val type = when {
                rbCharacter.isChecked -> "character"
                rbWorld.isChecked -> "world"
                rbStyle.isChecked -> "style"
                rbOutline.isChecked -> "outline"
                else -> "other"
            }
            val prompt = originalPrompt?.copy(
                title = title,
                content = content,
                type = type,
                updateTime = System.currentTimeMillis()
            ) ?: WritingPrompt(
                title = title,
                content = content,
                type = type
            )
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    if (prompt.id == 0L) {
                        appDb.writingPromptDao.insert(prompt)
                    } else {
                        appDb.writingPromptDao.update(prompt)
                    }
                }
                dismiss()
            }
        }
    }

    companion object {
        fun newInstance(prompt: WritingPrompt?): PromptEditDialog {
            return PromptEditDialog().apply {
                arguments = Bundle().apply {
                    if (prompt != null) {
                        putString("prompt", GSON.toJson(prompt))
                    }
                }
            }
        }
    }
}
