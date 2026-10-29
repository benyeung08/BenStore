package com.benstore.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * 用 PackageInstaller Session API 安裝 APK。
 * 相比 ACTION_VIEW 的好處：不跳出應用程式、可以連續排隊多個、能收到安裝結果。
 */
object Installer {

    const val ACTION_RESULT = "com.benstore.app.action.INSTALL_RESULT"

    /** sessionId -> packageName，結果廣播只帶 sessionId，用這張表換回包名 */
    private val pending = ConcurrentHashMap<Int, String>()

    fun remember(sessionId: Int, packageName: String) { pending[sessionId] = packageName }
    fun lookup(sessionId: Int): String? = pending[sessionId]
    fun forget(sessionId: Int) { pending.remove(sessionId) }

    private fun pendingFlags(): Int =
        PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)

    /**
     * 建立安裝工作階段並 commit。回傳 sessionId。
     * 系統會彈一次安裝確認（第三方应用無法繞過），結果由 [InstallResultReceiver] 接收。
     */
    fun install(context: Context, file: File, packageName: String): Int {
        val pm = context.packageManager
        val installer = pm.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(packageName)

        val sessionId = installer.createSession(params)
        remember(sessionId, packageName)
        val session = installer.openSession(sessionId)
        try {
            session.openWrite(packageName, 0, file.length()).use { out ->
                FileInputStream(file).use { input -> input.copyTo(out) }
                session.fsync(out)
            }
            val pi = PendingIntent.getBroadcast(
                context,
                sessionId,
                Intent(ACTION_RESULT).setPackage(context.packageName),
                pendingFlags()
            )
            session.commit(pi.intentSender)
        } catch (t: Throwable) {
            forget(sessionId)
            try { installer.abandonSession(sessionId) } catch (_: Throwable) { }
            throw t
        } finally {
            session.close()
        }
        return sessionId
    }
}

/**
 * 優先走 Session 安裝；失敗時退回系統安裝器，確保一定裝得上。
 *
 * 必須在背景執行：`Installer.install` 會把整個 APK 寫進 PackageInstaller 的
 * session（input.copyTo(out)），幾十 MB 的檔案放在主執行緒複製會卡住介面甚至 ANR。
 * 呼叫端原本是 `scope.launch { ... runInstall(...) }`（Main dispatcher），
 * 所以在這裡直接切到 IO；降級的 startActivity 則回到 Main 再發。
 */
suspend fun runInstall(context: Context, file: File, packageName: String) =
    withContext(Dispatchers.IO) {
        try {
            Installer.install(context, file, packageName)
        } catch (t: Throwable) {
            withContext(Dispatchers.Main) {
                RepoStore.installApk(context, file)
            }
        }
    }

class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Installer.ACTION_RESULT) return
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE
        )
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val pkg = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)
            ?: Installer.lookup(sessionId)
        Installer.forget(sessionId)

        val label = pkg?.let { repoLabel(context, it) } ?: "應用"

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // 系統彈出確認框，把 PendingIntent 轉發出去
            val more = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_INTENT)
            }
            if (more != null) {
                more.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(more)
            }
            return
        }

        if (status == PackageInstaller.STATUS_SUCCESS) {
            Notifier.installResult(context, label, true, null)
        } else {
            try {
                context.packageManager.packageInstaller.abandonSession(sessionId)
            } catch (_: Throwable) { }
            Notifier.installResult(
                context, label, false,
                message ?: statusText(status)
            )
        }
    }

    private fun statusText(status: Int): String = when (status) {
        PackageInstaller.STATUS_FAILURE_ABORTED -> "使用者取消"
        PackageInstaller.STATUS_FAILURE_BLOCKED -> "被系統封鎖"
        PackageInstaller.STATUS_FAILURE_CONFLICT -> "與已安裝應用衝突（包名或簽名不同）"
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "與裝置不相容"
        PackageInstaller.STATUS_FAILURE_INVALID -> "APK 無效"
        PackageInstaller.STATUS_FAILURE_STORAGE -> "儲存空間不足"
        else -> "安裝失敗（代碼 $status）"
    }

    /** 盡量從資料源拿應用名稱，拿不到就用包名 */
    private fun repoLabel(context: Context, packageName: String): String {
        val repo = RepoStore.repo
        if (repo != null) {
            val name = repo.find(packageName)?.name
            if (!name.isNullOrBlank()) return name
        }
        return try {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    packageName,
                    android.content.pm.PackageManager.PackageInfoFlags.of(0L)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(packageName, 0)
            }
            context.packageManager.getApplicationLabel(info.applicationInfo).toString()
        } catch (t: Throwable) {
            packageName
        }
    }
}
