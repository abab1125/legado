package io.legado.app.ui.book.read.ai

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.viewModels
import androidx.lifecycle.Observer
import com.google.android.material.appbar.MaterialToolbar
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.databinding.ActivityCharacterExtractBinding
import io.legado.app.ui.book.read.ai.CharacterExtractViewModel.CharacterExtractResult
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding

/**
 * 角色提取页面
 *
 * 流程：勾选章节后跳转至此页 → 流式显示提取日志 → 提取完成展示结果
 * → 用户看完全部角色后点击「保存角色」才写入知识库
 */
class CharacterExtractActivity : BaseActivity<ActivityCharacterExtractBinding>() {

    override val binding by viewBinding(ActivityCharacterExtractBinding::inflate)
    private val viewModel by viewModels<CharacterExtractViewModel>()

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
        val chapters = intent.getIntegerArrayListExtra(EXTRA_CHAPTERS) ?: run {
            toastOnUi("缺少章节选择")
            finish(); return
        }

        // 绑定 LiveData
        viewModel.logLiveData.observe(this, Observer { log ->
            binding.tvLog.text = log
            // 自动滚到底
            binding.tvLog.post { binding.tvLog.scrollTo(0, binding.tvLog.lineHeight * (binding.tvLog.lineCount - 1)) }
        })

        viewModel.resultsLiveData.observe(this, Observer { results ->
            if (results.isNotEmpty()) {
                showResults(results)
            }
        })

        viewModel.isLoadingLiveData.observe(this, Observer { loading ->
            if (!loading && viewModel.isDoneLiveData.value != true) {
                // 提取完成但没结果的 case 已在 errorLiveData 处理
            }
        })

        viewModel.errorLiveData.observe(this, Observer { error ->
            if (error != null) {
                appendLog("❌ $error")
                toastOnUi(error)
            }
        })

        viewModel.isDoneLiveData.observe(this, Observer { done ->
            binding.btnSave.isEnabled = done && (viewModel.resultsLiveData.value?.isNotEmpty() == true)
        })

        // 保存按钮
        binding.btnSave.setOnClickListener {
            viewModel.saveResults(bookName)
            toastOnUi("已保存 ${viewModel.resultsLiveData.value?.size ?: 0} 个角色到知识库")
            finish()
        }

        // 开始提取
        appendLog("📖 开始提取角色...")
        viewModel.startExtract(bookUrl, bookName, chapters)
    }

    private fun showResults(results: List<CharacterExtractResult>) {
        binding.tvLog.visibility = View.GONE
        binding.rvResults.visibility = View.VISIBLE

        val dp = resources.displayMetrics.density
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((8 * dp).toInt(), 0, (8 * dp).toInt(), 0)
        }

        for ((i, role) in results.withIndex()) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt())
                setBackgroundResource(io.legado.app.R.drawable.bg_card_white)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.setMargins(0, 0, 0, (8 * dp).toInt())
                layoutParams = lp
            }

            card.addView(TextView(this).apply {
                text = "${i + 1}. ${role.name}"
                textSize = 16f
                setTextColor(io.legado.app.lib.theme.primaryTextColor)
                setPadding(0, 0, 0, (4 * dp).toInt())
            })

            if (role.description.isNotBlank()) {
                card.addView(makeField("简介", role.description, dp))
            }
            if (role.personality.isNotBlank()) {
                card.addView(makeField("性格", role.personality, dp))
            }
            if (role.background.isNotBlank()) {
                card.addView(makeField("背景", role.background, dp))
            }
            if (role.speakingStyle.isNotBlank()) {
                card.addView(makeField("说话风格", role.speakingStyle, dp))
            }

            container.addView(card)
        }

        binding.rvResults.adapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {
            override fun getItemCount() = 1
            override fun onCreateViewHolder(p: ViewGroup, t: Int) =
                object : androidx.recyclerview.widget.RecyclerView.ViewHolder(container) {}
            override fun onBindViewHolder(h: androidx.recyclerview.widget.RecyclerView.ViewHolder, p: Int) {}
        }
    }

    private fun makeField(label: String, value: String, dp: Float): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (4 * dp).toInt(), 0, 0)

            addView(TextView(context).apply {
                text = "$label："
                textSize = 13f
                setTextColor(io.legado.app.lib.theme.secondaryText)
            })

            addView(TextView(context).apply {
                text = value
                textSize = 14f
                setTextColor(io.legado.app.lib.theme.primaryTextColor)
                setPadding(0, (2 * dp).toInt(), 0, 0)
            })
        }
    }

    private fun appendLog(text: String) {
        val current = viewModel.logLiveData.value ?: ""
        viewModel.logLiveData.postValue(if (current.isEmpty()) text else "$current\n$text")
    }
}
