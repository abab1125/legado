@file:Suppress("unused")

package io.legado.app.utils

import android.annotation.SuppressLint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.os.Build
import android.view.MenuItem
import android.widget.Toolbar
import androidx.core.content.ContextCompat
import io.legado.app.R
import io.legado.app.ui.widget.TitleBar

/**
 * 设置toolBar更多图标颜色
 */
@SuppressLint("ObsoleteSdkInt")
fun Toolbar.setMoreIconColor(color: Int) {
    val moreIcon = ContextCompat.getDrawable(context, R.drawable.ic_more)
    if (moreIcon != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        moreIcon.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_ATOP)
        overflowIcon = moreIcon
    }
}

/**
 * 在 TitleBar 右侧添加「+」按钮，点击触发 onAdd
 */
fun TitleBar.setAddButton(onAdd: () -> Unit) {
    menu.add(R.string.add).apply {
        setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        icon = resources.getDrawable(R.drawable.ic_add, context.theme)
    }
    toolbar.setOnMenuItemClickListener { onAdd(); true }
}