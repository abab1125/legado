package io.legado.app.ui.book.thought

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.SeekBar
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.databinding.DialogThoughtUnderlineStyleBinding
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.getPrimaryTextColor
import io.legado.app.ui.book.read.page.entities.TextLine
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.dpToPx
import io.legado.app.utils.viewbindingdelegate.viewBinding

class ThoughtUnderlineStyleDialog(
    private val currentStyle: TextLine.ThoughtUnderlineStyle = TextLine.ThoughtUnderlineStyle(),
    private val onConfirm: (TextLine.ThoughtUnderlineStyle) -> Unit
) : BaseDialogFragment(R.layout.dialog_thought_underline_style) {

    private val binding by viewBinding(DialogThoughtUnderlineStyleBinding::bind)
    private var selectedStyle = currentStyle.style
    private var selectedWeight = currentStyle.weight
    private var selectedColor = currentStyle.color

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
            root.setBackgroundColor(bg)
            tvTitle.setTextColor(textColor)
            tvWeightValue.setTextColor(textColor)
            tvCancel.setTextColor(textColor)
            tvColorValue.setTextColor(requireContext().getColor(R.color.accent))
        }
        initStyleChips()
        initWeightSeekBar()
        initColorButton()
        initPreview()
        initButtons()
    }

    private fun initStyleChips() = binding.run {
        chipGroupStyle.check(when (selectedStyle) {
            1 -> R.id.chip_solid
            2 -> R.id.chip_dashed
            3 -> R.id.chip_wave
            4 -> R.id.chip_dotted
            else -> R.id.chip_default
        })
        chipGroupStyle.setOnCheckedStateChangeListener { _, checkedIds ->
            selectedStyle = when (checkedIds.firstOrNull()) {
                R.id.chip_solid -> 1
                R.id.chip_dashed -> 2
                R.id.chip_wave -> 3
                R.id.chip_dotted -> 4
                else -> 0
            }
            updatePreview()
        }
    }

    private fun initWeightSeekBar() = binding.run {
        seekWeight.progress = ((selectedWeight - 0.5f) * 20).toInt()
        tvWeightValue.text = String.format("%.1fdp", selectedWeight)
        seekWeight.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                selectedWeight = 0.5f + progress / 20f
                tvWeightValue.text = String.format("%.1fdp", selectedWeight)
                updatePreview()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
    }

    private fun initColorButton() = binding.run {
        tvColorValue.setOnClickListener {
            // 简单的颜色切换：跟随强调色 -> 红色 -> 蓝色 -> 绿色 -> 跟随强调色
            selectedColor = when (selectedColor) {
                "" -> "#FF0000"
                "#FF0000" -> "#0000FF"
                "#0000FF" -> "#00FF00"
                else -> ""
            }
            tvColorValue.text = when (selectedColor) {
                "" -> "跟随强调色"
                "#FF0000" -> "红色"
                "#0000FF" -> "蓝色"
                "#00FF00" -> "绿色"
                else -> selectedColor
            }
            updatePreview()
        }
    }

    private fun initPreview() {
        updatePreview()
    }

    private fun updatePreview() = binding.run {
        val previewView = vwPreview
        previewView.post {
            val w = previewView.width.toFloat()
            val h = previewView.height.toFloat()
            val bitmap = android.graphics.Bitmap.createBitmap(
                w.toInt(), h.toInt(), android.graphics.Bitmap.Config.ARGB_8888
            )
            val canvas = Canvas(bitmap)
            val lineY = h * 0.7f
            val color = if (selectedColor.isNotEmpty()) {
                try { android.graphics.Color.parseColor(selectedColor) } catch (_: Exception) {
                    requireContext().getColor(R.color.accent)
                }
            } else {
                requireContext().getColor(R.color.accent)
            }
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                strokeWidth = selectedWeight.dpToPx()
                style = Paint.Style.STROKE
            }
            when (selectedStyle) {
                1 -> paint.pathEffect = null
                2 -> paint.pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
                3 -> paint.pathEffect = DashPathEffect(floatArrayOf(6f, 6f), 0f)
                4 -> paint.pathEffect = DashPathEffect(floatArrayOf(2f, 8f), 0f)
                else -> paint.pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
            }
            canvas.drawLine(0f, lineY, w, lineY, paint)
            previewView.background = android.graphics.drawable.BitmapDrawable(resources, bitmap)
        }
    }

    private fun initButtons() = binding.run {
        tvCancel.setOnClickListener { dismiss() }
        tvOk.setOnClickListener {
            onConfirm(TextLine.ThoughtUnderlineStyle(selectedStyle, selectedWeight, selectedColor))
            dismiss()
        }
    }
}
