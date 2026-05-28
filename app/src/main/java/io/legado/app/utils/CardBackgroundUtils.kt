package io.legado.app.utils

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView
import io.legado.app.R
import io.legado.app.lib.theme.cardBackground

/**
 * 递归遍历 View 树，将所有使用 background_card 静态颜色的 View
 * 替换为 ThemeStore 中用户自定义的卡片底色。
 */
fun Activity.applyCardBackground() {
    val newColor = cardBackground
    val defaultColor = ContextCompat.getColor(this, R.color.background_card)
    if (newColor == defaultColor) return
    window.decorView.post {
        applyCardBackgroundRecursive(window.decorView, newColor, defaultColor)
    }
}

private fun applyCardBackgroundRecursive(view: View, newColor: Int, defaultColor: Int) {
    if (view is MaterialCardView) {
        val bg = view.cardBackgroundColor
        if (bg != null && bg.defaultColor == defaultColor) {
            view.setCardBackgroundColor(newColor)
        }
    } else if (view.background != null) {
        try {
            val bg = view.background
            if (bg is android.graphics.drawable.ColorDrawable) {
                if (bg.color == defaultColor) {
                    view.setBackgroundColor(newColor)
                }
            }
        } catch (_: Exception) {}
    }
    if (view is ViewGroup) {
        for (i in 0 until view.childCount) {
            applyCardBackgroundRecursive(view.getChildAt(i), newColor, defaultColor)
        }
    }
}
