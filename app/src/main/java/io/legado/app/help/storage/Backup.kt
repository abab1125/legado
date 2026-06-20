package io.legado.app.help.storage

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.AppWebDav
import io.legado.app.help.DirectLinkUpload
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.readrecord.DetailedReadRecordHelper
import io.legado.app.model.BookCover
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.LogUtils
import io.legado.app.utils.compress.ZipUtils
import io.legado.app.utils.createFolderIfNotExist
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getFile
import io.legado.app.utils.getSharedPreferences
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.normalizeFileName
import io.legado.app.utils.openOutputStream
import io.legado.app.utils.outputStream
import io.legado.app.utils.writeToOutputStream
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import androidx.core.content.edit
import io.legado.app.model.VideoPlay.VIDEO_PREF_NAME
import kotlinx.coroutines.currentCoroutineContext

/**
 * 备份
 */
object Backup {

    val backupPath: String by lazy {
        appCtx.filesDir.getFile("backup").createFolderIfNotExist().absolutePath
    }
    val zipFilePath = "${appCtx.externalFiles.absolutePath}${File.separator}tmp_backup.zip"

    private const val TAG = "Backup"

    private val mutex = Mutex()

    private val backupFileNames by lazy {
        arrayOf(
            "bookshelf.json",
            "bookmark.json",
            "bookThoughts.json",
            "bookGroup.json",
            "bookSource.json",
            "rssSources.json",
            "rssStar.json",
            "replaceRule.json",
            "readRecord.json",
            "readRecord_detail.json",
            "searchHistory.json",
            "sourceSub.json",
            "txtTocRule.json",
            "httpTTS.json",
            "keyboardAssists.json",
            "dictRule.json",
            "servers.json",
            DirectLinkUpload.ruleFileName,
            ReadBookConfig.configFileName,
            ReadBookConfig.shareConfigFileName,
            ThemeConfig.configFileName,
            BookCover.configFileName,
            "config.xml",
            "videoConfig.xml"
        )
    }

    private fun getNowZipFileName(): String {
        val backupDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            .format(Date(System.currentTimeMillis()))
        val deviceName = AppConfig.webDavDeviceName
        return if (deviceName?.isNotBlank() == true) {
            "backup${backupDate}-${deviceName}.zip"
        } else {
            "backup${backupDate}.zip"
        }.normalizeFileName()
    }

    private fun shouldBackup(): Boolean {
        val lastBackup = LocalConfig.lastBackup
        return lastBackup + TimeUnit.DAYS.toMillis(1) < System.currentTimeMillis()
    }

    fun autoBack(context: Context) {
        if (shouldBackup()) {
            Coroutine.async {
                mutex.withLock {
                    if (shouldBackup()) {
                        val backupZipFileName = getNowZipFileName()
                        if (!AppWebDav.hasBackUp(backupZipFileName)) {
                            backup(context, AppConfig.backupPath)
                        } else {
                            LocalConfig.lastBackup = System.currentTimeMillis()
                        }
                    }
                }
            }.onError {
                AppLog.put("自动备份失败\n${it.localizedMessage}")
            }
        }
    }

    suspend fun backupLocked(context: Context, path: String?) {
        mutex.withLock {
            withContext(IO) {
                backup(context, path)
            }
        }
    }

    private suspend fun backup(context: Context, path: String?) {
        LogUtils.d(TAG, "开始备份 path:$path")
        LocalConfig.lastBackup = System.currentTimeMillis()
        val aes = BackupAES()
        FileUtils.delete(backupPath)
        writeListToJson(appDb.bookDao.all, "bookshelf.json", backupPath)
        writeListToJson(appDb.bookmarkDao.all, "bookmark.json", backupPath)
        writeListToJson(appDb.bookThoughtDao.all, "bookThoughts.json", backupPath)
        writeListToJson(appDb.bookGroupDao.all, "bookGroup.json", backupPath)
        writeListToJson(appDb.bookSourceDao.all, "bookSource.json", backupPath)
        writeListToJson(appDb.rssSourceDao.all, "rssSources.json", backupPath)
        writeListToJson(appDb.rssStarDao.all, "rssStar.json", backupPath)
        writeListToJson(appDb.replaceRuleDao.all, "replaceRule.json", backupPath)
        writeListToJson(appDb.readRecordDao.all, "readRecord.json", backupPath)
        writeDetailedReadRecordJson(backupPath)
        writeListToJson(appDb.searchKeywordDao.all, "searchHistory.json", backupPath)
        writeListToJson(appDb.ruleSubDao.all, "sourceSub.json", backupPath)
        writeListToJson(appDb.txtTocRuleDao.all, "txtTocRule.json", backupPath)
        writeListToJson(appDb.httpTTSDao.all, "httpTTS.json", backupPath)
        writeListToJson(appDb.keyboardAssistsDao.all, "keyboardAssists.json", backupPath)
        writeListToJson(appDb.dictRuleDao.all, "dictRule.json", backupPath)
        GSON.toJson(appDb.serverDao.all).let { json ->
            aes.runCatching {
                encryptBase64(json)
            }.getOrDefault(json).let {
                FileUtils.createFileIfNotExist(backupPath + File.separator + "servers.json")
                    .writeText(it)
            }
        }
        currentCoroutineContext().ensureActive()
        GSON.toJson(ReadBookConfig.configList).let {
            FileUtils.createFileIfNotExist(backupPath + File.separator + ReadBookConfig.configFileName)
                .writeText(it)
        }
        GSON.toJson(ReadBookConfig.shareConfig).let {
            FileUtils.createFileIfNotExist(backupPath + File.separator + ReadBookConfig.shareConfigFileName)
                .writeText(it)
        }
        GSON.toJson(ThemeConfig.configList).let {
            FileUtils.createFileIfNotExist(backupPath + File.separator + ThemeConfig.configFileName)
                .writeText(it)
        }
        DirectLinkUpload.getConfig()?.let {
            FileUtils.createFileIfNotExist(backupPath + File.separator + DirectLinkUpload.ruleFileName)
                .writeText(GSON.toJson(it))
        }
        BookCover.getConfig()?.let {
            FileUtils.createFileIfNotExist(backupPath + File.separator + BookCover.configFileName)
                .writeText(GSON.toJson(it))
        }
        currentCoroutineContext().ensureActive()
        appCtx.getSharedPreferences(backupPath, "config")?.let { sp ->
            val edit = sp.edit()
            appCtx.defaultSharedPreferences.all.forEach { (key, value) ->
                if (BackupConfig.keyIsNotIgnore(key)) {
                    when (key) {
                        PreferKey.webDavPassword -> {
                            edit.putString(key, aes.runCatching {
                                encryptBase64(value.toString())
                            }.getOrDefault(value.toString()))
                        }

                        else -> when (value) {
                            is Int -> edit.putInt(key, value)
                            is Boolean -> edit.putBoolean(key, value)
                            is Long -> edit.putLong(key, value)
                            is Float -> edit.putFloat(key, value)
                            is String -> edit.putString(key, value)
                        }
                    }
                }
            }
            edit.commit()
        }
        currentCoroutineContext().ensureActive()
        appCtx.getSharedPreferences(backupPath, "videoConfig")?.let { sp ->
            sp.edit(commit = true) {
                appCtx.getSharedPreferences(VIDEO_PREF_NAME, Context.MODE_PRIVATE).all.forEach { (key, value) ->
                    when (value) {
                        is Int -> putInt(key, value)
                        is Boolean -> putBoolean(key, value)
                        is Long -> putLong(key, value)
                        is Float -> putFloat(key, value)
                        is String -> putString(key, value)
                    }
                }
            }
        }
        currentCoroutineContext().ensureActive()
        val zipFileName = getNowZipFileName()
        val paths = arrayListOf(*backupFileNames)
        for (i in 0 until paths.size) {
            paths[i] = backupPath + File.separator + paths[i]
        }
        // 收集并备份字体文件
        if (BackupConfig.backupFont) {
            collectFontFiles().forEach { fontFile ->
                val target = backupPath + File.separator + "font" + File.separator + fontFile.name
                fontFile.copyTo(FileUtils.createFileIfNotExist(target), overwrite = true)
            }
            val fontDir = backupPath + File.separator + "font"
            if (File(fontDir).exists()) {
                File(fontDir).listFiles()?.forEach {
                    paths.add(it.absolutePath)
                }
            }
        }
        // 收集并备份主题背景图（仅本地文件）
        if (BackupConfig.backupThemeBg) {
            collectThemeBgFiles().forEach { bgFile ->
                val target =
                    backupPath + File.separator + "themeBg" + File.separator + bgFile.name
                bgFile.copyTo(FileUtils.createFileIfNotExist(target), overwrite = true)
            }
            val themeBgDir = backupPath + File.separator + "themeBg"
            if (File(themeBgDir).exists()) {
                File(themeBgDir).listFiles()?.forEach {
                    paths.add(it.absolutePath)
                }
            }
        }
        // 收集并备份阅读背景图
        if (BackupConfig.backupReadBg) {
            collectReadBgFiles().forEach { bgFile ->
                val target =
                    backupPath + File.separator + "readBg" + File.separator + bgFile.name
                bgFile.copyTo(FileUtils.createFileIfNotExist(target), overwrite = true)
            }
            val readBgDir = backupPath + File.separator + "readBg"
            if (File(readBgDir).exists()) {
                File(readBgDir).listFiles()?.forEach {
                    paths.add(it.absolutePath)
                }
            }
        }
        FileUtils.delete(zipFilePath)
        FileUtils.delete(zipFilePath.replace("tmp_", ""))
        val backupFileName = if (AppConfig.onlyLatestBackup) {
            "backup.zip"
        } else {
            zipFileName
        }
        if (ZipUtils.zipFiles(paths, zipFilePath)) {
            when {
                path.isNullOrBlank() -> {
                    copyBackup(context.getExternalFilesDir(null)!!, backupFileName)
                }

                path.isContentScheme() -> {
                    copyBackup(context, path.toUri(), backupFileName)
                }

                else -> {
                    copyBackup(File(path), backupFileName)
                }
            }
            try {
                AppWebDav.backUpWebDav(zipFileName)
            } catch (e: Exception) {
                AppLog.put("上传备份至webdav失败\n$e", e)
            }
        }
        FileUtils.delete(backupPath)
        FileUtils.delete(zipFilePath)
        currentCoroutineContext().ensureActive()
        ReadBookConfig.getAllPicBgStr().map {
            if (it.contains(File.separator)) {
                File(it)
            } else {
                appCtx.externalFiles.getFile("bg", it)
            }
        }.let {
            AppWebDav.upBgs(it.toTypedArray())
        }
    }

    private suspend fun writeListToJson(list: List<Any>, fileName: String, path: String) {
        currentCoroutineContext().ensureActive()
        withContext(IO) {
            if (list.isNotEmpty()) {
                LogUtils.d(TAG, "阅读备份 $fileName 列表大小 ${list.size}")
                val file = FileUtils.createFileIfNotExist(path + File.separator + fileName)
                file.outputStream().buffered().use {
                    GSON.writeToOutputStream(it, list)
                }
                LogUtils.d(TAG, "阅读备份 $fileName 写入大小 ${file.length()}")
            } else {
                LogUtils.d(TAG, "阅读备份 $fileName 列表为空")
            }
        }
    }

    private suspend fun writeDetailedReadRecordJson(path: String) {
        currentCoroutineContext().ensureActive()
        withContext(IO) {
            val exportJson = DetailedReadRecordHelper.buildExportJson(appDb.detailedReadRecordDao.all())
            if (exportJson != "[]") {
                LogUtils.d(TAG, "阅读备份 readRecord_detail.json 列表非空")
                val file = FileUtils.createFileIfNotExist(path + File.separator + "readRecord_detail.json")
                file.writeText(exportJson)
                LogUtils.d(TAG, "阅读备份 readRecord_detail.json 写入大小 ${file.length()}")
            } else {
                LogUtils.d(TAG, "阅读备份 readRecord_detail.json 列表为空")
            }
        }
    }

    @Throws(Exception::class)
    @Suppress("SameParameterValue")
    private fun copyBackup(context: Context, uri: Uri, fileName: String) {
        val treeDoc = DocumentFile.fromTreeUri(context, uri)!!
        treeDoc.findFile(fileName)?.delete()
        val fileDoc = treeDoc.createFile("", fileName)
            ?: throw NoStackTraceException("创建文件失败")
        val outputS = fileDoc.openOutputStream()
            ?: throw NoStackTraceException("打开OutputStream失败")
        outputS.use {
            FileInputStream(zipFilePath).use { inputS ->
                inputS.copyTo(outputS)
            }
        }
    }

    @Throws(Exception::class)
    @Suppress("SameParameterValue")
    private fun copyBackup(rootFile: File, fileName: String) {
        FileInputStream(File(zipFilePath)).use { inputS ->
            val file = FileUtils.createFileIfNotExist(rootFile, fileName)
            FileOutputStream(file).use { outputS ->
                inputS.copyTo(outputS)
            }
        }
    }

    /**
     * 收集所有阅读排版配置中引用的字体文件
     */
    private fun collectFontFiles(): List<File> {
        val fontFiles = mutableListOf<File>()
        ReadBookConfig.configList.forEach { config ->
            if (config.textFont.isNotEmpty()) {
                var fontFile = File(config.textFont)
                // 如果不是完整路径，尝试在本地字体目录查找
                if (!fontFile.exists()) {
                    val localFontPath =
                        FileUtils.getPath(appCtx.externalFiles, "font", config.textFont)
                    fontFile = File(localFontPath)
                }
                // 如果是 content URI，读取流复制到临时文件
                if (!fontFile.exists() && config.textFont.startsWith("content://")) {
                    try {
                        val uri = Uri.parse(config.textFont)
                        val fileName = uri.lastPathSegment
                            ?: "${config.textFont.hashCode()}.ttf"
                        val tempFile = File(backupPath, "font_cache${File.separator}$fileName")
                        tempFile.parentFile?.mkdirs()
                        appCtx.contentResolver.openInputStream(uri)?.use { input ->
                            tempFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        if (tempFile.exists() && tempFile.length() > 0) {
                            fontFile = tempFile
                        }
                    } catch (_: Exception) {
                    }
                }
                if (fontFile.exists()) {
                    fontFiles.add(fontFile)
                }
            }
        }
        return fontFiles.distinctBy { it.name }
    }

    /**
     * 收集所有主题配置中的本地背景图文件（排除网络URL）
     */
    private fun collectThemeBgFiles(): List<File> {
        val bgFiles = mutableListOf<File>()
        ThemeConfig.configList.forEach { config ->
            val path = config.backgroundImgPath ?: return@forEach
            if (!path.startsWith("http")) {
                val bgFile = File(path)
                if (bgFile.exists()) {
                    bgFiles.add(bgFile)
                }
            }
        }
        return bgFiles.distinctBy { it.name }
    }

    /**
     * 收集所有阅读背景图文件
     */
    private fun collectReadBgFiles(): List<File> {
        val bgFiles = mutableListOf<File>()
        ReadBookConfig.getAllPicBgStr().forEach { path ->
            val bgFile = if (path.contains(File.separator)) {
                File(path)
            } else {
                appCtx.externalFiles.getFile("bg", path)
            }
            if (bgFile.exists()) {
                bgFiles.add(bgFile)
            }
        }
        return bgFiles.distinctBy { it.name }
    }

    fun clearCache() {
        FileUtils.delete(backupPath)
        FileUtils.delete(zipFilePath)
    }
}
