package com.benstore.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/**
 * 負責：抓 F-Droid 倉庫索引（index-v1.jar）、讀取圖示、下載 APK、呼叫安裝。
 * 索引與 APK 都放在 app 的 cache 目錄，不需要任何儲存權限。
 */
object RepoStore {

    /** 預設倉庫，改成你自己的倉庫也可以 */
    const val DEFAULT_REPO = "https://f-droid.org/repo"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    @Volatile
    var index: RepoIndex? = null
        private set

    @Volatile
    private var zip: ZipFile? = null

    private const val INDEX_ENTRY = "index-v1.json"

    fun cacheJar(context: Context): File = File(context.cacheDir, "index-v1.jar")

    /**
     * 載入索引。已載入過就直接回傳，force = true 時重新下載。
     */
    suspend fun load(context: Context, repoUrl: String, force: Boolean = false): RepoIndex =
        withContext(Dispatchers.IO) {
            index?.let { if (!force) return@withContext it }

            val jar = cacheJar(context)
            if (force || !jar.exists() || jar.length() < 1024L) {
                download(repoUrl.trimEnd('/') + "/index-v1.jar", jar, null)
            }
            zip?.close()
            val zf = ZipFile(jar)
            val entry = zf.getEntry(INDEX_ENTRY)
                ?: throw IllegalStateException("索引檔裡找不到 $INDEX_ENTRY")
            val json = zf.getInputStream(entry).bufferedReader().use { it.readText() }

            val parsed = parseIndex(json, repoUrl.trimEnd('/'))
            zip = zf
            index = parsed
            parsed
        }

    /** 從 jar 裡讀出應用圖示 */
    fun iconBitmap(iconName: String): Bitmap? {
        if (iconName.isBlank()) return null
        val zf = zip ?: return null
        val candidates = listOf("icons/$iconName", "icons-640/$iconName", iconName)
        for (c in candidates) {
            val e = zf.getEntry(c) ?: continue
            return try {
                zf.getInputStream(e).use { BitmapFactory.decodeStream(it) }
            } catch (t: Throwable) {
                null
            }
        }
        return null
    }

    /** 下載 APK，回傳檔案；onProgress(downloadedBytes, totalBytes) */
    suspend fun downloadApk(
        context: Context,
        apk: ApkInfo,
        onProgress: (Long, Long) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val index0 = index ?: error("尚未載入索引")
        val dir = File(context.cacheDir, "apks")
        if (!dir.exists()) dir.mkdirs()
        val out = File(dir, apk.apkName.substringAfterLast('/'))

        download(index0.apkUrl(apk.apkName), out) { d, t ->
            withContext(Dispatchers.Main) { onProgress(d, t) }
        }
        out
    }

    private suspend fun download(
        url: String,
        out: File,
        progress: (suspend (Long, Long) -> Unit)?
    ) {
        val tmp = File(out.parentFile, out.name + ".part")
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("下載失敗 HTTP ${response.code}")
            val body = response.body ?: error("回應為空")
            val total = body.contentLength()
            var done = 0L
            body.byteStream().use { input ->
                FileOutputStream(tmp).use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val r = input.read(buf)
                        if (r <= 0) break
                        output.write(buf, 0, r)
                        done += r
                        if (progress != null) progress(done, total)
                    }
                }
            }
        }
        if (out.exists()) out.delete()
        if (!tmp.renameTo(out)) tmp.copyTo(out, overwrite = true)
    }

    /** 是否已取得「允許安裝未知應用」 */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

    /** 跳到系統設定頁授權安裝 */
    fun openInstallPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            )
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    /** 呼叫系統安裝器安裝 APK */
    fun installApk(context: Context, file: File) {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            context.packageName + ".fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        context.startActivity(intent)
    }

    /** 開瀏覽器 */
    fun openUrl(context: Context, url: String) {
        if (url.isBlank()) return
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "未知大小"
        val kb = bytes / 1024.0
        return when {
            kb < 1024 -> String.format(Locale.getDefault(), "%.0f KB", kb)
            else -> String.format(Locale.getDefault(), "%.1f MB", kb / 1024.0)
        }
    }
}
