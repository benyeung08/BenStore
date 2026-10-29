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

object Notifier {

    const val CH_UPDATE = "benstore_updates"
    const val CH_RESULT = "benstore_results"
    private const val ID_UPDATE = 1001
    private const val ID_RESULT = 1002

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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
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
        // 通知 ID 依應用名稱產生：原本用固定 ID，批次更新時後一個結果會蓋掉前一個，
        // 更新 5 個應用只會看到最後那個的成敗。
        val id = ID_RESULT + (appName.hashCode() and 0x0FFF)
        NotificationManagerCompat.from(context).notify(id, builder.build())
    }
}
