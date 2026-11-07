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
import java.math.BigInteger
import java.security.MessageDigest
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 負責：抓自建 repo.json、快取圖示、下載 APK（含 sha256 驗證）、呼叫安裝。
 * 所有檔案都放在 app 的 cache 目錄，不需要任何儲存權限。
 */
object RepoStore {

    /** 改成你自己的 repo.json 位置（GitHub Raw / Pages / 任何 HTTPS 連結都可以） */
    const val DEFAULT_REPO =
        "https://raw.githubusercontent.com/<your-account>/BenStore/main/repo/repo.json"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    @Volatile
    var repo: AppRepo? = null
        private set

    private val iconMem = object : LinkedHashMap<String, Bitmap>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean =
            size > 200
    }
    private val iconLock = Any()

    /**
     * 快取檔依資料源網址分開。
     * 舊版只用一個 `repo.json`：換了新網址又剛好連不上時，會把**上一個**
     * 資料源的內容當成這次的結果顯示，使用者以為切換成功，其實看到的是舊清單。
     */
    private fun repoCache(context: Context, url: String): File =
        File(context.cacheDir, "repo-${md5(url).take(16)}.json")
    private fun iconDir(context: Context): File = File(context.cacheDir, "icons").apply { mkdirs() }
    private fun apkDir(context: Context): File = File(context.cacheDir, "apks").apply { mkdirs() }

    private fun md5(s: String): String =
        BigInteger(1, MessageDigest.getInstance("MD5").digest(s.toByteArray())).toString(16)
            .padStart(32, '0')

    /**
     * 載入資料源。成功就寫入快取；失敗且有快取就退回快取，讓離線時仍能瀏覽。
     * token 非空時會帶上 Authorization（私有 repo.json 用）。
     */
    suspend fun load(context: Context, url: String, token: String = ""): AppRepo =
        withContext(Dispatchers.IO) {
            val b = Request.Builder().url(url)
            if (token.isNotBlank()) b.header("Authorization", "Bearer $token")
            val request = b.build()
            val text = try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        error(
                            if (response.code == 404 && token.isBlank())
                                "HTTP 404（若這是私有倉庫，請先登入 GitHub）"
                            else "HTTP ${response.code}"
                        )
                    }
                    val body = response.body ?: error("回應為空")
                    // 原本用 body.string() 會把整個回應讀進記憶體。
                    // 指到錯誤的檔案或惡意資料源時，幾百 MB 會在解析前就 OOM。
                    val bytes = readCapped(body)
                        ?: error(
                            "資料源超過 ${MAX_RESPONSE_BYTES / 1024 / 1024} MB 上限，或內容為空"
                        )
                    String(bytes, Charsets.UTF_8)
                }
            } catch (t: Throwable) {
                // 協程被取消（例如使用者又換了一個資料源網址）時不能走快取回退，
                // 否則取消掉的舊請求仍會把畫面更新成舊資料。
                if (t is kotlinx.coroutines.CancellationException) throw t
                val cached = repoCache(context, url)
                val fallback = if (cached.exists()) {
                    runCatching { parseRepo(cached.readText(), url) }.getOrNull()
                } else null
                if (fallback != null) {
                    repo = fallback
                    return@withContext fallback
                }
                throw t
            }

            val parsed = parseRepo(text, url)
            runCatching { repoCache(context, url).writeText(text) }
            repo = parsed
            parsed
        }

    /**
     * 快取的圖示最長邊上限（px）。
     * 應用圖示常見 512×512，ARGB_8888 一張就 1 MB；
     * 記憶體快取 200 張原圖會吃掉 200 MB，滑久一點就 OOM。
     * 列表顯示 44 dp、詳情顯示 72 dp（xxhdpi 約 216 px），
     * 取 256 px 兼顧清晰度與記憶體：512 的圖示降為 1/4，約 256 KB/張。
     */
    private const val ICON_MAX_PX = 256

    /** 先算 inSampleSize 再解碼，避免一次把整張原圖讀進記憶體 */
    private fun decodeSampled(bytes: ByteArray, maxPx: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        val w = opts.outWidth
        val h = opts.outHeight
        if (w <= 0 || h <= 0) return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        var sample = 1
        while (maxOf(w, h) / sample > maxPx) sample *= 2
        val real = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, real)
    }

    /**
     * 單次非 APK 回應的上限（bytes）：圖示與 repo.json 共用。
     * 兩者原本都是直接 `body.bytes()` / `body.string()` 把整個回應讀進記憶體，
     * 指到錯誤檔案或惡意資料源時，幾百 MB 會在解析前就 OOM。
     * 8 MB 對圖示很寬裕，對 repo.json 也足以放數千筆應用。
     */
    private const val MAX_RESPONSE_BYTES = 8 * 1024 * 1024

    /** 邊讀邊收，最多讀 [MAX_RESPONSE_BYTES]；超過回傳 null */
    private fun readCapped(body: okhttp3.ResponseBody): ByteArray? {
        val declared = body.contentLength()
        if (declared > MAX_RESPONSE_BYTES) return null
        val buf = ByteArray(64 * 1024)
        val out = java.io.ByteArrayOutputStream()
        var total = 0
        body.byteStream().use { input ->
            while (true) {
                val r = input.read(buf)
                if (r <= 0) break
                total += r
                if (total > MAX_RESPONSE_BYTES) return null
                out.write(buf, 0, r)
            }
        }
        return out.toByteArray().takeIf { it.isNotEmpty() }
    }

    /** 先查記憶體，再查磁碟，最後才真的下載 */
    suspend fun loadIcon(context: Context, url: String): Bitmap? = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext null

        synchronized(iconLock) { iconMem[url] }?.let { return@withContext it }

        val file = File(iconDir(context), md5(url) + ".icon")
        if (file.exists() && file.length() > 0L && file.length() <= MAX_RESPONSE_BYTES) {
            val bytes = runCatching { file.readBytes() }.getOrNull()
            if (bytes != null && bytes.isNotEmpty()) {
                decodeSampled(bytes, ICON_MAX_PX)?.let {
                    synchronized(iconLock) { iconMem[url] = it }
                    return@withContext it
                }
            }
        }

        return@withContext try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body ?: return@use null
                val bytes = readCapped(body) ?: return@use null
                runCatching { file.writeBytes(bytes) }
                val bmp = decodeSampled(bytes, ICON_MAX_PX)
                if (bmp != null) synchronized(iconLock) { iconMem[url] = bmp }
                bmp
            }
        } catch (t: Throwable) {
            null
        }
    }

    /** 快取檔名用的套件名前綴（去掉不合法字元） */
    private fun safePkg(packageName: String): String =
        packageName.replace(Regex("[^A-Za-z0-9._]"), "_")

    /**
     * 已下載過的 APK 直接複用，不重複下載。
     *
     * 檔名包含 url + sha256 的摘要：舊版只用「包名 + versionCode」，
     * 若同一個版本號重新構建過 APK（換了下載網址、但 sha256 留空），
     * 會一直命中舊快取，使用者永遠裝到舊包。
     */
    fun localApk(context: Context, packageName: String, version: AppVersion): File {
        val tag = md5(version.url + "|" + version.githubAsset?.assetId + "|" + version.sha256)
            .take(10)
        return File(apkDir(context), "${safePkg(packageName)}-${version.versionCode}-$tag.apk")
    }

    /**
     * 下載成功後清掉同套件的舊快取，避免 cacheDir/apks 無限長大。
     * 只清「60 秒前」的檔案：另一個協程可能正同時下載同套件另一版本，
     * 剛寫好的 out 不能被這裡刪掉。暫存檔由各自的 finally 清理，不在此處理。
     */
    private fun pruneOldApks(dir: File, packageName: String, keep: File) {
        val prefix = "${safePkg(packageName)}-"
        val cutoff = System.currentTimeMillis() - 60_000L
        dir.listFiles { f ->
            f.isFile && f.name.startsWith(prefix) && f.name.endsWith(".apk") &&
                f.absolutePath != keep.absolutePath && f.lastModified() < cutoff
        }?.forEach { runCatching { it.delete() } }
    }

    /**
     * 下載 APK（有 sha256 就驗證），回傳檔案。
     *
     * 私密來源兩種寫法：
     *  - `githubAsset`：走 GitHub API 抓私有 release asset，需要 token
     *  - `requiresAuth` + 一般 url：直接帶 Authorization 下載
     */
    suspend fun downloadApk(
        context: Context,
        packageName: String,
        version: AppVersion,
        token: String = "",
        onProgress: (Long, Long) -> Unit
    ): File = withContext(Dispatchers.IO) {
        if (version.requiresAuth && token.isBlank()) {
            error("此版本需要登入 GitHub 才能下載")
        }

        val out = localApk(context, packageName, version)
        if (out.exists() && out.length() > 0L) {
            if (version.sha256.isBlank() || sha256Of(out).equals(version.sha256, true)) {
                withContext(Dispatchers.Main) { onProgress(out.length(), out.length()) }
                return@withContext out
            }
            out.delete()   // 快取內容與校驗值不符，重新下載
        }

        // 唯一暫存檔：兩個協程同時下載同一版本（例如批次更新 + 背景檢查）
        // 原本會寫同一個 .part 互相覆蓋，SHA 校驗隨機失敗；失敗時檔案還會殘留。
        val tmp = File(
            out.parentFile,
            out.name + ".part." + java.util.UUID.randomUUID().toString()
        )
        try {
            fetchTo(version, token, tmp) { d, t ->
                withContext(Dispatchers.Main) { onProgress(d, t) }
            }
            if (out.exists()) out.delete()
            if (!tmp.renameTo(out)) tmp.copyTo(out, overwrite = true)

            if (version.sha256.isNotBlank()) {
                val actual = sha256Of(out)
                if (!actual.equals(version.sha256, true)) {
                    out.delete()
                    error("SHA-256 不符：\n期望 ${version.sha256}\n實際 $actual")
                }
            }
            pruneOldApks(apkDir(context), packageName, out)
            out
        } finally {
            tmp.delete()
        }
    }

    /**
     * 實際抓檔。手動處理重新導向：GitHub 的 asset API 會 302 到
     * 物件儲存服務的簽名 URL，若把 Authorization 一起帶過去，
     * 該服務會以 400 拒絕，所以換 host 時要主動拿掉。
     */
    private suspend fun fetchTo(
        version: AppVersion,
        token: String,
        out: File,
        onProgress: suspend (Long, Long) -> Unit
    ) {
        val asset = version.githubAsset
        var url: String = if (asset != null) {
            "https://api.github.com/repos/${asset.owner}/${asset.repo}/releases/assets/${asset.assetId}"
        } else {
            version.url
        }
        var useApiAccept = asset != null

        repeat(5) { hop ->
            val b = Request.Builder().url(url)
            if (token.isNotBlank()) b.header("Authorization", "Bearer $token")
            if (useApiAccept) b.header("Accept", "application/octet-stream")

            client.newCall(b.build()).execute().use { response ->
                if (response.isRedirect) {
                    val loc = response.header("Location")
                        ?: error("重新導向缺少 Location")
                    val sameHost = runCatching {
                        java.net.URI(loc).host == java.net.URI(url).host
                    }.getOrDefault(false)
                    if (!sameHost) useApiAccept = false   // 換 host 就不再帶 API header
                    url = loc
                    return@use
                }
                if (!response.isSuccessful) {
                    error(
                        when (response.code) {
                            401 -> "HTTP 401：token 無效或已撤銷，請重新登入"
                            403 -> "HTTP 403：沒有這個私有倉庫的權限（或 API 速率超限）"
                            404 -> "HTTP 404：找不到資產，請確認 assetId 正確"
                            else -> "下載失敗 HTTP ${response.code}"
                        }
                    )
                }
                val body = response.body ?: error("回應為空")
                val total = body.contentLength()
                var done = 0L
                // 進度節流：原本每 64 KB 就 withContext(Main) 一次，
                // 50 MB 的 APK 會觸發約 800 次執行緒切換與重組，介面會卡。
                // 改成「進度變化超過 2% 或距離上次超過 120 ms」才回報。
                var lastDone = 0L
                var lastAt = 0L
                val step = if (total > 0) (total / 50).coerceAtLeast(64 * 1024) else 256 * 1024
                body.byteStream().use { input ->
                    FileOutputStream(out).use { output ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val r = input.read(buf)
                            if (r <= 0) break
                            output.write(buf, 0, r)
                            done += r
                            val now = System.currentTimeMillis()
                            if (done - lastDone >= step || now - lastAt >= 120) {
                                lastDone = done
                                lastAt = now
                                onProgress(done, total)
                            }
                        }
                    }
                }
                onProgress(done, total)   // 收尾一定回報一次，避免停在 99%
                return
            }
        }
        error("重新導向次數過多")
    }

    fun sha256Of(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val r = input.read(buf)
                if (r <= 0) break
                md.update(buf, 0, r)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** 是否已取得「允許安裝未知應用」 */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

    /** 跳到系統設定頁授權安裝 */
    fun openInstallPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        // 部分 OEM 沒有實作這個設定頁，startActivity 會直接拋
        // ActivityNotFoundException；原本沒保護，點「去授權」就閃退。
        runCatching {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            )
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    /** 呼叫系統安裝器安裝 APK（Session 安裝失敗時的降級方案） */
    fun installApk(context: Context, file: File) {
        // 降級路徑原本沒有例外處理：裝置上若沒有能處理
        // application/vnd.android.package-archive 的 Activity，
        // 或 FileProvider 找不到檔案，會直接崩潰而不是回到 UI 顯示錯誤。
        runCatching {
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
    }

    /**
     * 開啟外部連結。
     *
     * ⚠️ 這裡的字串可能來自 repo.json 的 `webSite` / `sourceCode`，
     * 是**外部可控輸入**。原本直接 `Uri.parse(url)` + `startActivity`：
     *  - `file://` / `content://` 可讓惡意資料源喚起任意元件；
     *  - `javascript:`、`mailto:`（沒裝郵件 App）或畸形 URI 找不到處理者，
     *    會拋 ActivityNotFoundException 直接閃退。
     *
     * 現在只放行 http/https，且整段包 runCatching。
     */
    fun openUrl(context: Context, url: String): Boolean {
        if (url.isBlank()) return false
        val uri = runCatching { Uri.parse(url.trim()) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return false
        if (uri.host.isNullOrBlank()) return false
        return runCatching {
            val intent = Intent(Intent.ACTION_VIEW, uri)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        }.getOrDefault(false)
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
