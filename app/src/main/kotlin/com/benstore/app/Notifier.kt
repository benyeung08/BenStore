package com.benstore.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicInteger

object Notifier {

    const val CH_UPDATE = "benstore_updates"
    const val CH_RESULT = "benstore_results"
    private const val ID_UPDATE = 1001
    private const val ID_RESULT_BASE = 1002

    // 原本用應用名稱雜湊映射到 4096 個槽位，批次更新時兩個不同應用
    // 仍可能撞 ID 而互相覆蓋。改成嚴格遞增，每個安裝結果各佔一個 ID；
    // 重啟後歸零只會覆蓋到舊通知，無害。
    private val resultIds = AtomicInteger(ID_RESULT_BASE)

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        val updates = NotificationChannel(
            CH_UPDATE, "有新版本", NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = "BenStore 發現已安裝的應用有新版本" }
        val results = NotificationChannel(
            CH_RESULT, "安裝結果", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "更新安裝成功或失敗" }
        nm.createNotificationChannels(listOf(updates, results))
    }

    fun canNotify(context: Context): Boolean {
        // 兩個條件都要查：部分 OEM 上 POST_NOTIFICATIONS 仍是 granted，
        // 但使用者在系統設定裡把這個 App 的通知總開關關掉了，
        // notify() 會靜默失敗（不拋例外），UI 卻顯示「已授權」。
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun immutable() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

    /** 點通知直接進「更新」分頁 */
    private fun contentIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_UPDATES, true)
        }
        return PendingIntent.getActivity(
            context, 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or immutable()
        )
    }

    fun updatesAvailable(context: Context, updates: List<AppUpdate>) {
        if (!canNotify(context) || updates.isEmpty()) return
        createChannels(context)

        val n = updates.size
        val lines = updates.take(10).joinToString("\n") {
            "• ${it.app.name}  ${it.installedVersionName} → ${it.version.versionName}"
        }
        val builder = NotificationCompat.Builder(context, CH_UPDATE)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("$n 個應用有新版本")
            .setContentText(updates.take(3).joinToString("、") { it.app.name } + if (n > 3) " 等" else "")
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines))
            .setContentIntent(contentIntent(context))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
        NotificationManagerCompat.from(context).notify(ID_UPDATE, builder.build())
    }

    fun installResult(context: Context, appName: String, ok: Boolean, message: String?) {
        if (!canNotify(context)) return
        createChannels(context)
        val builder = NotificationCompat.Builder(context, CH_RESULT)
            .setSmallIcon(
                if (ok) android.R.drawable.stat_sys_download_done
                else android.R.drawable.stat_notify_error
            )
            .setContentTitle(if (ok) "$appName 更新完成" else "$appName 更新失敗")
            .setContentText(message ?: if (ok) "已安裝到最新版本" else "請檢查安裝權限")
            .setContentIntent(contentIntent(context))
            .setAutoCancel(true)
        val id = resultIds.getAndIncrement()
        NotificationManagerCompat.from(context).notify(id, builder.build())
    }
}
