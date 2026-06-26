package io.legado.app.ui.book.toc

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.databinding.DialogChapterSummaryBinding
import io.legado.app.help.book.BookHelp
import io.legado.app.help.config.AiConfig
import io.legado.app.help.http.okHttpClient
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class ChapterSummaryDialog : BaseDialogFragment(R.layout.dialog_chapter_summary) {

    private val binding by viewBinding(DialogChapterSummaryBinding::bind)

    private var book: Book? = null
    private var chapter: BookChapter? = null

    companion object {
        private const val ARG_BOOK = "book"
        private const val ARG_CHAPTER = "chapter"

        fun newInstance(book: Book, chapter: BookChapter): ChapterSummaryDialog {
            return ChapterSummaryDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_BOOK, GSON.toJson(book))
                    putString(ARG_CHAPTER, GSON.toJson(chapter))
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        setLayout(1f, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        arguments?.let { args ->
            GSON.fromJsonObject<Book>(args.getString(ARG_BOOK, "")).onSuccess {
                book = it
            }
            GSON.fromJsonObject<BookChapter>(args.getString(ARG_CHAPTER, "")).onSuccess {
                chapter = it
            }
        }

        binding.tvTitle.text = chapter?.let { "编辑《${it.title}》概要" } ?: "章节概要"

        val existingSummary = chapter?.variableMap?.get("summary")
        if (!existingSummary.isNullOrBlank()) {
            binding.etSummary.setText(existingSummary)
        }

        binding.ivClose.setOnClickListener { dismiss() }
        binding.btnAiGenerate.setOnClickListener { generateSummary() }
        binding.btnSave.setOnClickListener { saveSummary() }
    }

    private fun generateSummary() {
        val book = book ?: return
        val chapter = chapter ?: return
        val apiUrl = AiConfig.apiUrl
        val apiKey = AiConfig.apiKey
        val model = AiConfig.model

        if (apiUrl.isBlank() || apiKey.isBlank()) {
            toastOnUi("请先在 AI 助手中配置 API URL 和 Key")
            return
        }

        binding.progressBar.visibility = View.VISIBLE

        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val content = BookHelp.getContent(book, chapter)
                        ?: throw Exception("本章暂无缓存内容")

                    val truncatedContent = if (content.length > 6000) content.take(6000) + "..." else content
                    val promptText = "你是一个小说章节概要生成助手。请为以下章节内容生成一份简洁的章节概要（不超过200字），突出关键剧情转折和角色发展：\n\n${truncatedContent}"

                    val messagesJsonList = listOf(
                        mapOf("role" to "user", "content" to promptText)
                    )
                    val requestBodyMap = mapOf(
                        "model" to model,
                        "messages" to messagesJsonList,
                        "max_tokens" to 500
                    )
                    val jsonBody = GSON.toJson(requestBodyMap)
                    val body = jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType())

                    val request = Request.Builder()
                        .url(apiUrl)
                        .post(body)
                        .addHeader("Authorization", "Bearer $apiKey")
                        .addHeader("Content-Type", "application/json")
                        .build()

                    val aiHttpClient = okHttpClient.newBuilder()
                        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                        .callTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                        .build()

                    val responseString = aiHttpClient.newCall(request).execute().use { response ->
                        val bodyStr = response.body.string()
                        if (!response.isSuccessful) throw Exception("HTTP ${response.code}: $bodyStr")
                        bodyStr
                    }

                    val jsonObject = GSON.fromJsonObject<Map<String, Any>>(responseString).getOrThrow()
                    val choices = jsonObject["choices"] as? List<*>
                    val firstChoice = choices?.firstOrNull() as? Map<*, *>
                    val messageMap = firstChoice?.get("message") as? Map<*, *>
                    messageMap?.get("content") as? String ?: ""
                }
            }

            binding.progressBar.visibility = View.GONE

            result.onSuccess { text ->
                if (text.isNotBlank()) {
                    binding.etSummary.setText(text.trim())
                } else {
                    toastOnUi("AI 返回内容为空")
                }
            }.onFailure { error ->
                toastOnUi("AI 生成失败: ${error.localizedMessage ?: "未知错误"}")
            }
        }
    }

    private fun saveSummary() {
        val chapter = chapter ?: return
        val summaryText = binding.etSummary.text?.toString()?.trim() ?: ""

        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    chapter.putVariable("summary", summaryText.ifBlank { null })
                    chapter.update()
                }
            }.onSuccess {
                toastOnUi("概要已保存")
                dismiss()
            }.onFailure { error ->
                toastOnUi("保存失败: ${error.localizedMessage ?: "未知错误"}")
            }
        }
    }
}
