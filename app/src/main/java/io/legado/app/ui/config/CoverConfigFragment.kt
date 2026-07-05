package io.legado.app.ui.config

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.preference.Preference
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.prefs.SwitchPreference
import io.legado.app.lib.prefs.fragment.PreferenceFragment
import io.legado.app.lib.theme.primaryColor
import io.legado.app.model.BookCover
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.utils.FileUtils
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefString
import io.legado.app.utils.inputStream
import io.legado.app.utils.putPrefString
import io.legado.app.utils.readUri
import io.legado.app.utils.removePref
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import splitties.init.appCtx
import java.io.FileOutputStream

class CoverConfigFragment : PreferenceFragment(),
    SharedPreferences.OnSharedPreferenceChangeListener {

    private val requestCodeCover = 111
    private val requestCodeCoverDark = 112
    private val selectImage = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            when (it.requestCode) {
                requestCodeCover -> setCoverFromUri(PreferKey.defaultCover, uri)
                requestCodeCoverDark -> setCoverFromUri(PreferKey.defaultCoverDark, uri)
            }
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.pref_config_cover)
        upPreferenceSummary(PreferKey.defaultCover, getPrefString(PreferKey.defaultCover))
        upPreferenceSummary(PreferKey.defaultCoverDark, getPrefString(PreferKey.defaultCoverDark))
        findPreference<SwitchPreference>(PreferKey.coverShowAuthor)
            ?.isEnabled = getPrefBoolean(PreferKey.coverShowName)
        findPreference<SwitchPreference>(PreferKey.coverShowAuthorN)
            ?.isEnabled = getPrefBoolean(PreferKey.coverShowNameN)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity?.setTitle(R.string.cover_config)
        listView.setEdgeEffectColor(primaryColor)
        listView.post { setupCardBackgrounds() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        sharedPreferences ?: return
        when (key) {
            PreferKey.defaultCover,
            PreferKey.defaultCoverDark -> {
                upPreferenceSummary(key, getPrefString(key))
            }

            PreferKey.coverShowName -> {
                findPreference<SwitchPreference>(PreferKey.coverShowAuthor)
                    ?.isEnabled = getPrefBoolean(key)
                BookCover.upDefaultCover()
            }

            PreferKey.coverShowNameN -> {
                findPreference<SwitchPreference>(PreferKey.coverShowAuthorN)
                    ?.isEnabled = getPrefBoolean(key)
                BookCover.upDefaultCover()
            }

            PreferKey.coverShowAuthor,
            PreferKey.coverShowAuthorN -> {
                BookCover.upDefaultCover()
            }
        }
    }

    @SuppressLint("PrivateResource")
    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        when (preference.key) {
            "coverRule" -> showDialogFragment(CoverRuleConfigDialog())
            PreferKey.defaultCover,
            PreferKey.defaultCoverDark -> {
                val isDark = preference.key == PreferKey.defaultCoverDark
                val reqCode = if (isDark) requestCodeCoverDark else requestCodeCover
                val items = arrayListOf(
                    getString(R.string.select_image),
                    "选择图集压缩包"
                )
                if (!getPrefString(preference.key).isNullOrEmpty()) {
                    items.add(0, getString(R.string.delete))
                }
                context?.selector(items = items) { _, i ->
                    val action = items[i]
                    when (action) {
                        getString(R.string.delete) -> {
                            removePref(preference.key)
                            BookCover.upDefaultCover()
                        }
                        getString(R.string.select_image) -> {
                            selectImage.launch {
                                requestCode = reqCode
                                mode = HandleFileContract.IMAGE
                            }
                        }
                        "选择图集压缩包" -> {
                            selectImage.launch {
                                requestCode = reqCode
                                mode = HandleFileContract.FILE
                                allowExtensions = arrayOf("zip")
                            }
                        }
                    }
                }
            }
        }
        return super.onPreferenceTreeClick(preference)
    }

    private fun upPreferenceSummary(preferenceKey: String, value: String?) {
        val preference = findPreference<Preference>(preferenceKey) ?: return
        when (preferenceKey) {
            PreferKey.defaultCover,
            PreferKey.defaultCoverDark -> preference.summary = if (value.isNullOrBlank()) {
                getString(R.string.select_image)
            } else {
                value
            }

            else -> preference.summary = value
        }
    }

    private fun setCoverFromUri(preferenceKey: String, uri: Uri) {
        readUri(uri) { fileDoc, inputStream ->
            kotlin.runCatching {
                val isZip = fileDoc.name?.endsWith(".zip", true) == true
                val md5 = uri.inputStream(requireContext()).getOrThrow().use {
                    MD5Utils.md5Encode(it)
                }
                if (isZip) {
                    val targetDir = java.io.File(requireContext().externalFiles, "covers/$md5")
                    targetDir.mkdirs()
                    io.legado.app.utils.compress.ZipUtils.unZipToPath(inputStream, targetDir) { name: String ->
                        val n = name.lowercase()
                        n.endsWith(".jpg") || n.endsWith(".png") || n.endsWith(".webp") || n.endsWith(".jpeg") || n.endsWith(".bmp")
                    }
                    putPrefString(preferenceKey, targetDir.absolutePath)
                } else {
                    var file = requireContext().externalFiles
                    val suffix = if (fileDoc.name?.contains(".9.png", true) == true) {
                        ".9.png"
                    } else {
                        "." + fileDoc.name?.substringAfterLast(".")
                    }
                    val fileName = md5 + suffix
                    file = FileUtils.createFileIfNotExist(file, "covers", fileName)
                    FileOutputStream(file).use {
                        inputStream.copyTo(it)
                    }
                    putPrefString(preferenceKey, file.absolutePath)
                }
                BookCover.upDefaultCover()
            }.onFailure {
                appCtx.toastOnUi(it.localizedMessage)
            }
        }
    }

}