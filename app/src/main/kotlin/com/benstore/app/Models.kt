package com.benstore.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * 自建資料源格式（repo.json）
 *
 * {
 *   "meta": { "name": "BenStore", "updated": 1730000000000 },
 *   "apps": [
 *     {
 *       "packageName": "com.example.app",
 *       "name": "範例應用",
 *       "summary": "一句話簡介",
 *       "description": "完整說明，可用 \\n 換行",
 *       "icon": "https://.../icon.png",
 *       "categories": ["工具", "網路"],
 *       "author": "作者",
 *       "license": "GPL-3.0",
 *       "webSite": "https://...",
 *       "sourceCode": "https://github.com/...",
 *       "allowedEmails": ["a@gmail.com"],   // 選填：留空/省略 = 所有人可見
 *       "versions": [
 *         {
 *           "versionName": "1.2.0",
 *           "versionCode": 120,
 *           "url": "https://.../app-1.2.0.apk",
 *           "size": 1234567,
 *           "minSdk": 26,
 *           "sha256": "選填，填了下載後會驗證"
 *         }
 *       ]
 *     }
 *   ]
 * }
 */

/** 私有 Release 資產定位資訊 */
data class GitHubAsset(
    val owner: String,
    val repo: String,
    val assetId: Long
)

/**
 * 一個存取碼分級。
 * `hashes` 放的是存取碼的 SHA-256（用小工具產生），不存明文。
 */
data class Tier(
    val key: String,
    val label: String,
    val hashes: Set<String>
)

/** 一個可安裝的版本 */
data class AppVersion(
    val versionName: String,
    val versionCode: Long,
    val url: String,
    val size: Long,
    val minSdk: Int,
    val sha256: String,
    val requiresAuth: Boolean = false,
    val githubAsset: GitHubAsset? = null
)

/** 一個應用 */
data class RepoApp(
    val packageName: String,
    val name: String,
    val summary: String,
    val description: String,
    val iconUrl: String,
    val categories: List<String>,
    val author: String,
    val license: String,
    val webSite: String,
    val sourceCode: String,
    val versions: List<AppVersion>,
    val allowedEmails: List<String> = emptyList(),
    val requiredTiers: List<String> = emptyList()
)

/** 整份資料源 */
class AppRepo(
    val name: String,
    val updated: Long,
    val apps: List<RepoApp>,
    val sourceUrl: String,
    val tiers: Map<String, Tier> = emptyMap()
) {
    private val byPackage = apps.associateBy { it.packageName }

    fun find(packageName: String): RepoApp? = byPackage[packageName]

    /** 依雜湊找出對應的分級（兌換存取碼用） */
    fun tierForHash(hash: String): Tier? {
        val h = hash.trim().lowercase()
        return tiers.values.firstOrNull { it.hashes.contains(h) }
    }

    /** 分級定義的原始 JSON，給設定頁顯示有哪些分級可兌換 */
    fun tierList(): List<Tier> = tiers.values.toList()

    /**
     * 過濾出看得見的應用。
     * `grantedTiers` 是本機已用存取碼解鎖的分級。
     */
    fun visibleTo(account: Account?, grantedTiers: Set<String> = emptySet()): List<RepoApp> =
        apps.filter { isVisibleTo(it, account, grantedTiers) }

    /** 最新版本 = versionCode 最大的那個 */
    fun latest(app: RepoApp): AppVersion? = app.versions.maxByOrNull { it.versionCode }

    /** 依版本碼找特定版本（更新比對用） */
    fun versionOf(app: RepoApp, versionCode: Long): AppVersion? =
        app.versions.firstOrNull { it.versionCode == versionCode }
}

private fun str(o: JSONObject, key: String): String =
    o.opt(key)?.takeIf { it != JSONObject.NULL }?.toString() ?: ""

private fun longOf(o: JSONObject, key: String): Long {
    val v = o.opt(key) ?: return 0L
    return when (v) {
        is Number -> v.toLong()
        else -> v.toString().trim().toLongOrNull() ?: 0L
    }
}

private fun intOf(o: JSONObject, key: String): Int {
    val v = o.opt(key) ?: return 0
    return when (v) {
        is Number -> v.toInt()
        else -> v.toString().trim().toIntOrNull() ?: 0
    }
}

fun parseRepo(json: String, sourceUrl: String): AppRepo {
    val root = JSONObject(json)
    val meta = root.optJSONObject("meta")
    val name = meta?.let { str(it, "name") }?.ifBlank { "BenStore" } ?: "BenStore"
    val updated = meta?.let { longOf(it, "updated") } ?: 0L

    val apps = mutableListOf<RepoApp>()
    val arr: JSONArray = root.optJSONArray("apps") ?: JSONArray()
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val pkg = str(o, "packageName")
        if (pkg.isBlank()) continue

        val cats = mutableListOf<String>()
        val catsArr = o.optJSONArray("categories")
        if (catsArr != null) {
            for (c in 0 until catsArr.length()) {
                val v = catsArr.optString(c)
                if (!v.isNullOrBlank()) cats.add(v)
            }
        }

        val versions = mutableListOf<AppVersion>()
        val verArr = o.optJSONArray("versions")
        if (verArr != null) {
            for (v in 0 until verArr.length()) {
                val vo = verArr.optJSONObject(v) ?: continue
                val url = str(vo, "url")
                val asset = vo.optJSONObject("githubAsset")?.let { a ->
                    val owner = str(a, "owner")
                    val repo = str(a, "repo")
                    val id = longOf(a, "assetId")
                    if (owner.isBlank() || repo.isBlank() || id <= 0L) null
                    else GitHubAsset(owner, repo, id)
                }
                // 有 githubAsset 就不需要 url；兩者都沒有才跳過
                if (url.isBlank() && asset == null) continue
                versions.add(
                    AppVersion(
                        versionName = str(vo, "versionName"),
                        versionCode = longOf(vo, "versionCode"),
                        url = url,
                        size = longOf(vo, "size"),
                        minSdk = intOf(vo, "minSdk"),
                        sha256 = str(vo, "sha256").trim(),
                        requiresAuth = vo.optBoolean("requiresAuth", false) || asset != null,
                        githubAsset = asset
                    )
                )
            }
        }
        if (versions.isEmpty()) continue   // 沒有可安裝版本就跳過

        // 分級：只給特定帳號看的應用
        val allow = mutableListOf<String>()
        val allowArr = o.optJSONArray("allowedEmails")
        if (allowArr != null) {
            for (a in 0 until allowArr.length()) {
                val v = allowArr.optString(a).trim().lowercase()
                if (v.isNotBlank()) allow.add(v)
            }
        }

        // 分級：屬於哪些存取碼分級才看得到
        val tiersNeeded = mutableListOf<String>()
        val tierArr = o.optJSONArray("requiredTiers")
        if (tierArr != null) {
            for (t in 0 until tierArr.length()) {
                val v = tierArr.optString(t).trim().lowercase()
                if (v.isNotBlank()) tiersNeeded.add(v)
            }
        }

        apps.add(
            RepoApp(
                packageName = pkg,
                name = str(o, "name").ifBlank { pkg },
                summary = str(o, "summary"),
                description = str(o, "description"),
                iconUrl = str(o, "icon").trim(),
                categories = cats,
                author = str(o, "author"),
                license = str(o, "license"),
                webSite = str(o, "webSite"),
                sourceCode = str(o, "sourceCode"),
                versions = versions.sortedByDescending { it.versionCode },
                allowedEmails = allow,
                requiredTiers = tiersNeeded
            )
        )
    }

    // 頂層 tiers：分級定義，每個分級放存取碼的 SHA-256
    val tiers = LinkedHashMap<String, Tier>()
    val tObj: JSONObject = root.optJSONObject("tiers") ?: JSONObject()
    val tKeys = tObj.keys()
    while (tKeys.hasNext()) {
        val key = tKeys.next().trim().lowercase()
        if (key.isBlank()) continue
        val d = tObj.optJSONObject(key) ?: continue
        val label = str(d, "label").ifBlank { key }
        val hashes = LinkedHashSet<String>()
        val hArr = d.optJSONArray("hashes")
        if (hArr != null) {
            for (i in 0 until hArr.length()) {
                val v = hArr.optString(i).trim().lowercase()
                if (v.isNotBlank()) hashes.add(v)
            }
        }
        if (hashes.isNotEmpty()) tiers[key] = Tier(key, label, hashes)
    }

    return AppRepo(name, updated, apps, sourceUrl, tiers)
}
