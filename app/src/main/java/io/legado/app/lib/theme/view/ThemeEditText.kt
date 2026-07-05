package io.legado.app.lib.theme.view

import android.content.Context
import android.os.Build
import android.util.AttributeSet
import com.google.android.material.textfield.TextInputEditText

/**
 * 主题感知的 EditText，继承自 TextInputEditText。
 * TextInputEditText 是 Material 组件库推荐的输入框，与 TextInputLayout 配套使用，
 * 着色由 MaterialComponents 主题自动处理，无需手动 applyTint。
 */
class ThemeEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : TextInputEditText(context, attrs) {

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            isLocalePreferredLineHeightForMinimumUsed = false
        }
    }
}

