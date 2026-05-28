package io.legado.app.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import io.legado.app.lib.theme.accentColor

/**
 * 封面底部阅读进度条，完全自绘，无 ProgressBar 样式包袱
 */
class ReadingProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var progress: Int = 0
        set(value) {
            field = value.coerceIn(0, 100)
            invalidate()
        }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x26FFFFFF  // rgba(255,255,255,0.15)
        style = Paint.Style.FILL
    }

    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.accentColor
        style = Paint.Style.FILL
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val height = (3 * resources.displayMetrics.density).toInt()
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), height)
    }

    override fun onDraw(canvas: Canvas) {
        // 轨道
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), trackPaint)
        // 进度
        if (progress > 0) {
            val w = width * progress / 100f
            canvas.drawRect(0f, 0f, w, height.toFloat(), progressPaint)
        }
    }
}
