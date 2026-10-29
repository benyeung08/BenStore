package com.benstore.app

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat

/** 裝置上已安裝的某個應用 */
data class InstalledApp(
    val packageName: String,
    val versionName: String,
    val versionCode: Long
)

/** 一筆待更新：從 installedVersion 升級到 version */
data class AppUpdate(
    val app: RepoApp,
    val version: AppVersion,
    val installedVersionName: String,
    val installedVersionCode: Long
)

object UpdateScanner {

    /** 裝置上所有已安裝應用（包名 -> 版本） */
    @Suppress("DEPRECATION")
    fun installed(context: Context): Map<String, InstalledApp> {
        val pm = context.packageManager
        val list: List<PackageInfo> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0L))
        } else {
            pm.getInstalledPackages(0)
        }
        val out = HashMap<String, InstalledApp>(list.size)
        for (info in list) {
            out[info.packageName] = InstalledApp(
                packageName = info.packageName,
                versionName = info.versionName ?: "?",
                versionCode = PackageInfoCompat.getLongVersionCode(info)
            )
        }
        return out
    }

    /**
     * 比對資料源最新版本與已安裝版本。
     * 排除：自己、被忽略的版本、版本沒比較新、裝置不相容。
     */
    fun scan(context: Context, repo: AppRepo): List<AppUpdate> {
        val installed = installed(context)
        val ignored = Prefs.ignored(context)
        val self = context.packageName
        val sdk = Build.VERSION.SDK_INT

        val out = ArrayList<AppUpdate>()
        for (app in repo.apps) {
            if (app.packageName == self) continue
            val inst = installed[app.packageName] ?: continue

            val latest = repo.latest(app) ?: continue
            if (latest.versionCode <= inst.versionCode) continue
            if (ignored.contains("${app.packageName}:${latest.versionCode}")) continue
            if (latest.minSdk > sdk) continue

            out.add(AppUpdate(app, latest, inst.versionName, inst.versionCode))
        }
        return out.sortedBy { it.app.name.lowercase() }
    }

    /** 已安裝、且資料源裡找得到的應用數量（「追蹤中」顯示用） */
    fun trackedCount(context: Context, repo: AppRepo): Int {
        val installed = installed(context)
        val self = context.packageName
        return repo.apps.count { it.packageName != self && installed.containsKey(it.packageName) }
    }
}
