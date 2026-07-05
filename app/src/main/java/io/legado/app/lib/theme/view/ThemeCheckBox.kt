package io.legado.app.lib.theme.view

import android.content.Context
import android.util.AttributeSet
import com.google.android.material.checkbox.MaterialCheckBox

/**
 * 主题感知的 CheckBox，继承自 MaterialCheckBox。
 * MaterialCheckBox 在 MaterialComponents 主题下会自动从 colorPrimary 着色，
 * 无需手动 applyTint。
 *
 * 保留 setOnUserCheckedChangeListener 以区分用户操作与代码设值触发的 checked 变化。
 */
class ThemeCheckBox(context: Context, attrs: AttributeSet) : MaterialCheckBox(context, attrs) {

    private var isUserAction = false

    override fun performClick(): Boolean {
        isUserAction = true
        val result = super.performClick()
        isUserAction = false
        return result
    }

    fun setOnUserCheckedChangeListener(listener: ((Boolean) -> Unit)?) {
        if (listener == null) {
            return super.setOnCheckedChangeListener(null)
        }
        super.setOnCheckedChangeListener { _, isChecked ->
            if (isUserAction) {
                listener.invoke(isChecked)
            }
        }
    }

}

