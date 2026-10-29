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

    override suspend fun doWork(): ListenableWorker.Result {
        val ctx = applicationContext
        return try {
            // 授權碼過期就自動失效，不用等使用者手動移除
            License.purgeIfExpired(ctx)

            if (Prefs.wifiOnly(ctx) && !isUnmetered(ctx)) {
                return ListenableWorker.Result.success()
            }

            val url = Prefs.repoUrl(ctx)
            // 每次都抓最新的 repo.json，否則永遠比不到新版本
            val repo = RepoStore.load(ctx, url, AuthSettings.token(ctx))
            val updates = UpdateScanner.scan(ctx, repo)
            Prefs.setLastCheck(ctx, System.currentTimeMillis())

            if (updates.isNotEmpty()) {
                if (Prefs.autoInstall(ctx)) {
                    // 背景自動下載並送出安裝請求（每個會彈一次系統確認）
                    for (u in updates) {
                        try {
                            val file = RepoStore.downloadApk(
                                ctx, u.app.packageName, u.version, AuthSettings.token(ctx)
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
            ListenableWorker.Result.retry()
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

    /** 依目前設定重新排程（關閉自動檢查就取消） */
    fun schedule(context: Context) {
        val wm = WorkManager.getInstance(context)
        if (!Prefs.autoCheck(context)) {
            wm.cancelUniqueWork(PERIODIC)
            return
        }
        val hours = Prefs.intervalHours(context).coerceAtLeast(1).toLong()
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                if (Prefs.wifiOnly(context)) NetworkType.UNMETERED else NetworkType.CONNECTED
            )
            .build()
        val request = PeriodicWorkRequestBuilder<UpdateWorker>(hours, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun cancel(context: Context) = WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)

    /** 立刻檢查一次（不管自動檢查有沒有開） */
    fun checkNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<UpdateWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(ONCE, ExistingWorkPolicy.REPLACE, request)
    }
}
