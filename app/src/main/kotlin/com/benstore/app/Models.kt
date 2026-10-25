package com.benstore.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * 單一應用（來自 index-v1.json 的 apps 陣列）
 */
data class AppInfo(
    val packageName: String,
    val name: String,
    val summary: String,
    val description: String,
    val iconName: String,
    val categories: List<String>,
    val suggestedVersionCode: Long,
    val license: String,
    val author: String,
    val webSite: String,
    val sourceCode: String
)

/**
 * 可安裝的單一 APK（來自 index-v1.json 的 packages 對應表）
 */
data class ApkInfo(
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val apkName: String,
    val size: Long,
    val minSdkVersion: Int,
    val hash: String,
    val added: Long
)

/**
 * 整個倉庫索引
 */
class RepoIndex(
    val name: String,
    val address: String,
    val apps: List<AppInfo>,
    val packages: Map<String, List<ApkInfo>>
) {
    /** 取出建議版本（沒有就取最新的） */
    fun suggestedApk(app: AppInfo): ApkInfo? {
        val list = packages[app.packageName] ?: return null
        return list.firstOrNull { it.versionCode == app.suggestedVersionCode }
            ?: list.maxByOrNull { it.versionCode }
    }

    fun apkUrl(apkName: String): String = address.trimEnd('/') + "/" + apkName
}

private fun stripHtml(s: String): String =
    s.replace(Regex("<br\\s*/?>"), "\n")
        .replace(Regex("<[^>]*>"), "")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .trim()

fun parseIndex(json: String, fallbackAddress: String): RepoIndex {
    val root = JSONObject(json)
    val repo = root.optJSONObject("repo")
    val address = repo?.optString("address")?.takeIf { it.isNotBlank() } ?: fallbackAddress
    val name = repo?.optString("name")?.takeIf { it.isNotBlank() } ?: "F-Droid"

    val apps = mutableListOf<AppInfo>()
    val appsArray: JSONArray = root.optJSONArray("apps") ?: JSONArray()
    for (i in 0 until appsArray.length()) {
        val o = appsArray.optJSONObject(i) ?: continue
        val pkg = o.optString("packageName")
        if (pkg.isBlank()) continue
        val cats = mutableListOf<String>()
        val catsArray = o.optJSONArray("categories")
        if (catsArray != null) {
            for (c in 0 until catsArray.length()) {
                val v = catsArray.optString(c)
                if (v.isNotBlank()) cats.add(v)
            }
        }
        apps.add(
            AppInfo(
                packageName = pkg,
                name = o.optString("name").ifBlank { pkg },
                summary = o.optString("summary"),
                description = stripHtml(o.optString("description")),
                iconName = o.optString("icon"),
                categories = cats,
                suggestedVersionCode = o.optString("suggestedVersionCode").toLongOrNull()
                    ?: o.optLong("suggestedVersionCode", 0L),
                license = o.optString("license"),
                author = o.optString("authorName"),
                webSite = o.optString("webSite"),
                sourceCode = o.optString("sourceCode")
            )
        )
    }

    val packages = HashMap<String, List<ApkInfo>>()
    val pkgObj: JSONObject = root.optJSONObject("packages") ?: JSONObject()
    val keys = pkgObj.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        val arr = pkgObj.optJSONArray(key) ?: continue
        val list = mutableListOf<ApkInfo>()
        for (i in 0 until arr.length()) {
            val a = arr.optJSONObject(i) ?: continue
            val apkName = a.optString("apkName")
            if (apkName.isBlank()) continue
            list.add(
                ApkInfo(
                    packageName = key,
                    versionName = a.optString("versionName"),
                    versionCode = a.optLong("versionCode", 0L),
                    apkName = apkName,
                    size = a.optLong("size", 0L),
                    minSdkVersion = a.optInt("minSdkVersion", 0),
                    hash = a.optString("hash"),
                    added = a.optLong("added", 0L)
                )
            )
        }
        if (list.isNotEmpty()) packages[key] = list
    }

    return RepoIndex(name, address, apps, packages)
}
