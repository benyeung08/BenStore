package com.benstore.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * 背景定期檢查更新：重新下載倉庫索引 → 比對已安裝版本 → 發通知。
 * 由 WorkManager 排程，開機後會自動恢復，且遵守 Doze 與省電限制。
 */
class UpdateWorker(appContext: Context, params: WorkerParameters) :
    androidx.work.CoroutineWorker(appContext, params) {

    companion object {
        const val KEY_MANUAL = "manual_check"
        private const val MAX_RETRIES = 3
    }

    override suspend fun doWork(): ListenableWorker.Result {
        val ctx = applicationContext
        val manual = inputData.getBoolean(KEY_MANUAL, false)
        return try {
            // 授權碼過期就自動失效，不用等使用者手動移除
            License.purgeIfExpired(ctx)

            // 手動檢查是使用者主動按的，即使當下不是 Wi-Fi 也照做
            // （他已經知道會用流量）。原本這裡無條件 return success，
            // 結果「立即檢查」按下去什麼都沒發生、lastCheck 也不更新，
            // UI 等 3 秒後顯示舊時間，看起來像壞掉。
            if (!manual && Prefs.wifiOnly(ctx) && !isUnmetered(ctx)) {
                return ListenableWorker.Result.success()
            }

            val url = Prefs.repoUrl(ctx)
            // AuthSettings.token() 會讀 Keystore 加密的偏好設定（磁碟 I/O）。
            // CoroutineWorker 預設跑在 Dispatchers.Default，明確切到 IO。
            val token = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                AuthSettings.token(ctx)
            }
            // 每次都抓最新的 repo.json，否則永遠比不到新版本
            val repo = RepoStore.load(ctx, url, token)

            // 分級必須在背景鏈路也生效：原本這裡直接把 repo.apps 全部拿去比對，
            // 未授權帳號會收到「看不到」的應用更新通知，autoInstall 開啟時
            // 甚至會把那些 APK 抓下來送出安裝請求，等於繞過 UI 的門禁。
            val account = Auth.current(ctx)
            val tiers = Access.allTiers(ctx)
            val updates = UpdateScanner.scan(ctx, repo)
                .filter { isVisibleTo(it.app, account, tiers) }
            Prefs.setLastCheck(ctx, System.currentTimeMillis())

            if (updates.isNotEmpty()) {
                if (Prefs.autoInstall(ctx)) {
                    // 背景自動下載並送出安裝請求（每個會彈一次系統確認）
                    for (u in updates) {
                        try {
                            val file = RepoStore.downloadApk(
                                ctx, u.app.packageName, u.version, token
                            ) { _, _ -> }
                            runInstall(ctx, file, u.app.packageName)
                        } catch (_: Throwable) { }
                    }
                } else {
                    Notifier.updatesAvailable(ctx, updates)
                }
            }
            ListenableWorker.Result.success()
        } catch (t: Throwable) {
            // 原本所有錯誤都 retry()：資料源網址填錯、JSON 格式壞掉這種
            // 永久性錯誤也會被 WorkManager 無限期指數退避重試，白耗電。
            // 只對前幾次（可能是暫時性網路抖動）重試，超過就放棄。
            if (t is kotlinx.coroutines.CancellationException) throw t
            if (runAttemptCount < MAX_RETRIES) ListenableWorker.Result.retry()
            else {
                android.util.Log.w(
                    "BenStore.UpdateWorker",
                    "更新檢查放棄（已重試 $runAttemptCount 次）：${t.message}"
                )
                ListenableWorker.Result.failure()
            }
        }
    }

    private fun isUnmetered(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return true
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }
}

object UpdateScheduler {

    private const val PERIODIC = "benstore_periodic_check"
    private const val ONCE = "benstore_check_now"

    /**
     * 依目前設定重新排程（關閉自動檢查就取消）。
     *
     * ⚠️ 原本每次 MainActivity.onCreate 都無條件用 UPDATE 重新 enqueue，
     * 這會**重置週期計時器**：使用者只要每隔幾小時開一次 App（間隔 12 小時），
     * 下次執行時間就被一路往後推，背景檢查永遠不會真的跑。
     *
     * 改成只有在「設定簽名」改變時才 UPDATE；其餘情況直接略過，
     * 讓已排好的工作保留原本的執行時間。設定簽名存進 Prefs，重開機也在。
     */
    fun schedule(context: Context) {
        val wm = WorkManager.getInstance(context)
        if (!Prefs.autoCheck(context)) {
            wm.cancelUniqueWork(PERIODIC)
            Prefs.setScheduleSig(context, "")
            return
        }
        val hours = Prefs.intervalHours(context).coerceAtLeast(1).toLong()
        val wifiOnly = Prefs.wifiOnly(context)
        val sig = "$hours|$wifiOnly"

        if (Prefs.scheduleSig(context) == sig) return   // 設定沒變，不打擾既有排程

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
            )
            .build()
        val request = PeriodicWorkRequestBuilder<UpdateWorker>(hours, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
        Prefs.setScheduleSig(context, sig)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
        // 取消後下次 schedule() 要能重新排，不能因為簽名相同被略過
        Prefs.setScheduleSig(context.applicationContext, "")
    }

    /** 立刻檢查一次（不管自動檢查有沒有開、不管是不是 Wi-Fi） */
    fun checkNow(context: Context) {
        val data = androidx.work.Data.Builder()
            .putBoolean(UpdateWorker.KEY_MANUAL, true)
            .build()
        val request = OneTimeWorkRequestBuilder<UpdateWorker>()
            .setInputData(data)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(ONCE, ExistingWorkPolicy.REPLACE, request)
    }
}
