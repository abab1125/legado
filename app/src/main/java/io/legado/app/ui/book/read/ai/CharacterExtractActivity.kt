package io.legado.app.ui.book.read.ai

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.viewModels
import androidx.lifecycle.Observer
import io.legado.app.base.BaseActivity
import io.legado.app.databinding.ActivityCharacterExtractBinding
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.ui.book.read.ai.CharacterExtractViewModel.CharacterExtractResult
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding

/**
 * 角色提取页面
 *
 * 流程：勾选章节后跳转至此页 → 流式显示提取日志 → 提取完成展示可编辑的角色卡
 * → 用户编辑满意后点击「保存角色」才写入知识库
 */
class CharacterExtractActivity : BaseActivity<ActivityCharacterExtractBinding>() {

    override val binding by viewBinding(ActivityCharacterExtractBinding::inflate)
    private val viewModel by viewModels<CharacterExtractViewModel>()
    private val editableRoles = mutableListOf<CharacterExtractResult>()

    companion object {
        const val EXTRA_BOOK_URL = "bookUrl"
        const val EXTRA_BOOK_NAME = "bookName"
        const val EXTRA_CHAPTERS = "chapters"
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        // 获取参数
        val bookUrl = intent.getStringExtra(EXTRA_BOOK_URL) ?: run {
            toastOnUi("缺少书籍信息")
            finish(); return
        }
        val bookName = intent.getStringExtra(EXTRA_BOOK_NAME) ?: "未命名"
        val chapters = intent.getIntArrayExtra(EXTRA_CHAPTERS)?.toList() ?: run {
            toastOnUi("缺少章节选择")
            finish(); return
        }

        // 绑定 LiveData
        viewModel.logLiveData.observe(this, Observer { log ->
            binding.tvLog.text = log
            binding.svLog.post { binding.svLog.fullScroll(View.FOCUS_DOWN) }
        })

        viewModel.errorLiveData.observe(this, Observer { error ->
            if (error != null) {
                val current = binding.tvLog.text?.toString() ?: ""
                binding.tvLog.text = if (current.isEmpty()) "❌ $error" else "$current\n❌ $error"
                toastOnUi(error)
            }
        })

        viewModel.isDoneLiveData.observe(this, Observer { done ->
            if (done) {
                val results = viewModel.resultsLiveData.value
                if (results.isNullOrEmpty()) {
                    toastOnUi("未提取到角色，请扩大章节范围")
                    finish()
                    return@Observer
                }
                showEditor(results)
            }
        })

        // 保存按钮
        binding.btnSave.setOnClickListener {
            collectEdits()
            viewModel.saveResults(bookName)
            toastOnUi("已保存 ${editableRoles.size} 个角色到知识库")
            finish()
        }

        // 开始提取
        viewModel.startExtract(bookUrl, bookName, chapters)
    }

    private fun showEditor(results: List<CharacterExtractResult>) {
        // 切到编辑视图
        binding.svLog.visibility = View.GONE
        binding.svEdit.visibility = View.VISIBLE
        binding.btnSave.isEnabled = true

        val dp = resources.displayMetrics.density
        editableRoles.clear()
        editableRoles.addAll(results)

        binding.layoutEditor.removeAllViews()

        for ((i, role) in results.withIndex()) {
            val section = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt())
                setBackgroundColor(bottomBackground)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.setMargins(0, 0, 0, (12 * dp).toInt())
                layoutParams = lp
            }

            // 角色标题 + 删除按钮
            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            header.addView(TextView(this).apply {
                text = "角色 ${i + 1}"
                textSize = 16f
                setTextColor(primaryTextColor)
                setPadding(0, 0, 0, (4 * dp).toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })

            section.addView(header)

            // 每个字段
            section.addView(makeEditField("角色名", role.name, dp) { name ->
                editableRoles[i] = editableRoles[i].copy(name = name)
            })
            section.addView(makeEditField("简介", role.description, dp) { desc ->
                editableRoles[i] = editableRoles[i].copy(description = desc)
            })
            section.addView(makeEditField("性格", role.personality, dp) { v ->
                editableRoles[i] = editableRoles[i].copy(personality = v)
            })
            section.addView(makeEditField("背景", role.background, dp) { v ->
                editableRoles[i] = editableRoles[i].copy(background = v)
            })
            section.addView(makeEditField("说话风格", role.speakingStyle, dp) { v ->
                editableRoles[i] = editableRoles[i].copy(speakingStyle = v)
            })

            binding.layoutEditor.addView(section)
        }
    }

    private fun makeEditField(label: String, value: String, dp: Float, onEdit: (String) -> Unit): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (6 * dp).toInt(), 0, 0)
        }

        container.addView(TextView(this).apply {
            text = label
            textSize = 13f
            setTextColor(io.legado.app.lib.theme.secondaryText)
        })

        val editText = EditText(this).apply {
            setText(value)
            textSize = 14f
            setTextColor(primaryTextColor)
            setPadding(0, (2 * dp).toInt(), 0, 0)
            @Suppress("DEPRECATION")
            setBackgroundResource(io.legado.app.R.drawable.bg_edit)
            setPadding((6 * dp).toInt(), (4 * dp).toInt(), (6 * dp).toInt(), (4 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.setMargins(0, (2 * dp).toInt(), 0, 0) }

            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { onEdit(s?.toString() ?: "") }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })
        }

        container.addView(editText)
        return container
    }

    private fun collectEdits() {
        // 编辑已经通过 TextWatcher 实时同步到 editableRoles
        viewModel.setEditedResults(editableRoles)
    }
}
