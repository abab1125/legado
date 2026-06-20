package io.legado.app.ui.welcome

import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.View
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.postDelayed
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.gif.GifDrawable
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import io.legado.app.base.BaseActivity
import io.legado.app.constant.PreferKey
import io.legado.app.constant.Theme
import io.legado.app.data.appDb
import io.legado.app.databinding.ActivityWelcomeBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.main.MainActivity
import io.legado.app.utils.BitmapUtils
import io.legado.app.utils.fullScreen
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefString
import io.legado.app.utils.setStatusBarColorAuto
import io.legado.app.utils.startActivity
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import io.legado.app.utils.windowSize

open class WelcomeActivity : BaseActivity<ActivityWelcomeBinding>() {

    override val binding by viewBinding(ActivityWelcomeBinding::inflate)

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        if (intent.flags and Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT != 0) {
            // 避免从桌面启动程序后，会重新实例化入口类的activity
            finish()
        } else {
            val welcomeShowTime = getPrefInt(PreferKey.welcomeShowTime, 500)
            if (welcomeShowTime == 0) {
                startMainActivity()
            } else {
                binding.root.postDelayed(welcomeShowTime.toLong()) { startMainActivity() }
            }
        }
        binding.ivBook.setColorFilter(accentColor)
        binding.vwTitleLine.setBackgroundColor(accentColor)
    }

    override fun setupSystemBar() {
        fullScreen()
        setStatusBarColorAuto(backgroundColor, true, fullScreen)
        upNavigationBarColor()
    }

    override fun upBackgroundImage() {
        if (getPrefBoolean(PreferKey.customWelcome)) {
            val contentView = findViewById<View>(android.R.id.content)
            // 清除 contentView 上的背景色，使自定义背景图可见
            contentView.background = null
            val isDark = ThemeConfig.isDarkTheme()
            val imagePath = if (isDark) {
                getPrefString(PreferKey.welcomeImageDark)
            } else {
                getPrefString(PreferKey.welcomeImage)
            }
            imagePath?.let { path ->
                if (path.endsWith(".gif", ignoreCase = true)) {
                    // 使用Glide加载GIF
                    Glide.with(this)
                        .asGif()
                        .load(path)
                        .into(object : CustomTarget<GifDrawable>() {
                            override fun onResourceReady(
                                resource: GifDrawable,
                                transition: Transition<in GifDrawable>?
                            ) {
                                resource.start()
                                window.decorView.background = resource
                            }

                            override fun onLoadCleared(placeholder: Drawable?) {
                                // 清理资源
                            }

                            override fun onLoadFailed(errorDrawable: Drawable?) {
                                android.util.Log.e("WelcomeActivity", "加载GIF失败: $path")
                            }
                        })
                } else {
                    kotlin.runCatching {
                        val drawable = if (path.endsWith(".9.png")) {
                            BitmapUtils.decodeNinePatchDrawable(path)
                        } else {
                            val size = windowManager.windowSize
                            BitmapUtils.decodeBitmap(path, size.widthPixels, size.heightPixels)
                                ?.toDrawable(resources)
                        }
                        drawable?.let { window.decorView.background = it }
                    }.onFailure {
                        android.util.Log.e("WelcomeActivity", "加载启动页背景图失败: $path", it)
                    }
                }
            }
            if (isDark) {
                binding.tvLegado.visible(AppConfig.welcomeShowTextDark)
                binding.ivBook.visible(AppConfig.welcomeShowIconDark)
                binding.tvGzh.visible(AppConfig.welcomeShowTextDark)
            } else {
                binding.tvLegado.visible(AppConfig.welcomeShowText)
                binding.ivBook.visible(AppConfig.welcomeShowIcon)
                binding.tvGzh.visible(AppConfig.welcomeShowText)
            }
            return
        }
        // 启动页不显示通用背景图/背景色，仅显示主题设置中的启动页专属背景
    }

    private fun startMainActivity() {
        startActivity<MainActivity>()
        if (getPrefBoolean(PreferKey.defaultToRead) && appDb.bookDao.lastReadBook != null) {
            startActivity<ReadBookActivity>()
        }
        finish()
    }

}

class Launcher1 : WelcomeActivity()
class Launcher2 : WelcomeActivity()
class Launcher3 : WelcomeActivity()
class Launcher4 : WelcomeActivity()
class Launcher5 : WelcomeActivity()
class Launcher6 : WelcomeActivity()
class Launcher7 : WelcomeActivity()