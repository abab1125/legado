package io.legado.app.ui.main

import android.os.Bundle
import android.view.View
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.appDb
import io.legado.app.data.entities.KnowledgePoint
import io.legado.app.databinding.DialogKnowledgeEditBinding
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.GSON
import io.legado.app.utils.setLayout
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class KnowledgeEditDialog : BaseDialogFragment(R.layout.dialog_knowledge_edit) {

    private val binding by viewBinding(DialogKnowledgeEditBinding::bind)
    private var originalPoint: KnowledgePoint? = null

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, 0.85f)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) = binding.run {
        val pointJson = arguments?.getString("point")
        if (pointJson != null) {
            originalPoint = GSON.fromJson(pointJson, KnowledgePoint::class.java)
            originalPoint?.let { p ->
                etTitle.setText(p.title)
                etContent.setText(p.content)
                // 大类
                when (p.category) {
                    "character" -> rbCharacter.isChecked = true
                    "place" -> rbPlace.isChecked = true
                    "event" -> rbEvent.isChecked = true
                    "note" -> rbNote.isChecked = true
                    else -> rbOther.isChecked = true
                }
                // 人物子类
                if (p.category == "character") {
                    layoutSubCategory.visibility = View.VISIBLE
                    when (p.subCategory) {
                        "novel-character" -> {
                            rbSubNovel.isChecked = true
                            layoutNovelName.visibility = View.VISIBLE
                            etNovelName.setText(p.novelName)
                        }
                        else -> rbSubClassic.isChecked = true
                    }
                }
            }
        }

        // 人物大类选中时显示子分类区域
        rgCategory.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == R.id.rb_character) {
                layoutSubCategory.visibility = View.VISIBLE
            } else {
                layoutSubCategory.visibility = View.GONE
                layoutNovelName.visibility = View.GONE
            }
        }

        // 小说角色选中时显示小说名输入框
        rgSubCategory.setOnCheckedChangeListener { _, checkedId ->
            layoutNovelName.visibility =
                if (checkedId == R.id.rb_sub_novel) View.VISIBLE else View.GONE
        }

        btnSave.setOnClickListener {
            val title = etTitle.text.toString().trim()
            val content = etContent.text.toString().trim()
            if (title.isEmpty()) {
                toastOnUi("标题不能为空")
                return@setOnClickListener
            }
            val category = when {
                rbCharacter.isChecked -> "character"
                rbPlace.isChecked -> "place"
                rbEvent.isChecked -> "event"
                rbNote.isChecked -> "note"
                else -> "other"
            }
            val subCategory = if (category == "character" && rbSubNovel.isChecked) {
                "novel-character"
            } else ""
            val novelName = if (subCategory == "novel-character") {
                etNovelName.text.toString().trim()
            } else ""

            val point = originalPoint?.copy(
                title = title,
                content = content,
                category = category,
                subCategory = subCategory,
                novelName = novelName,
                updateTime = System.currentTimeMillis()
            ) ?: KnowledgePoint(
                title = title,
                content = content,
                category = category,
                subCategory = subCategory,
                novelName = novelName
            )
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    if (point.id == 0L) {
                        appDb.knowledgePointDao.insert(point)
                    } else {
                        appDb.knowledgePointDao.update(point)
                    }
                }
                dismiss()
            }
        }
    }

    companion object {
        fun newInstance(point: KnowledgePoint?): KnowledgeEditDialog {
            return KnowledgeEditDialog().apply {
                arguments = Bundle().apply {
                    if (point != null) {
                        putString("point", GSON.toJson(point))
                    }
                }
            }
        }
    }
}
