package com.benstore.app

import android.content.Context

/**
 * 所有使用者偏好都存在 SharedPreferences，背景 Worker 與 UI 共用同一份。
 */
object Prefs {

    private const val FILE = "benstore"
    private const val K_REPO = "repo_url"
    private const val K_AUTO_CHECK = "auto_check"
    private const val K_INTERVAL = "interval_hours"
    private const val K_WIFI_ONLY = "wifi_only"
    private const val K_AUTO_INSTALL = "auto_install"
    private const val K_IGNORED = "ignored"
    private const val K_LAST_CHECK = "last_check"

    // ---- 帳號（Google 登入）----
    private const val K_USER_ID = "user_id"
    private const val K_USER_EMAIL = "user_email"
    private const val K_USER_NAME = "user_name"
    private const val K_USER_PHOTO = "user_photo"
    private const val K_ID_TOKEN = "id_token"

    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // ---- 倉庫 ----
    fun repoUrl(c: Context): String = sp(c).getString(K_REPO, null) ?: RepoStore.DEFAULT_REPO
    fun setRepoUrl(c: Context, v: String) = sp(c).edit().putString(K_REPO, v).apply()

    // ---- 背景檢查 ----
    fun autoCheck(c: Context): Boolean = sp(c).getBoolean(K_AUTO_CHECK, true)
    fun setAutoCheck(c: Context, v: Boolean) = sp(c).edit().putBoolean(K_AUTO_CHECK, v).apply()

    fun intervalHours(c: Context): Int = sp(c).getInt(K_INTERVAL, 12)
    fun setIntervalHours(c: Context, v: Int) = sp(c).edit().putInt(K_INTERVAL, v).apply()

    fun wifiOnly(c: Context): Boolean = sp(c).getBoolean(K_WIFI_ONLY, true)
    fun setWifiOnly(c: Context, v: Boolean) = sp(c).edit().putBoolean(K_WIFI_ONLY, v).apply()

    /** 背景檢查時直接下載並送出安裝請求（每個會彈一次系統確認） */
    fun autoInstall(c: Context): Boolean = sp(c).getBoolean(K_AUTO_INSTALL, false)
    fun setAutoInstall(c: Context, v: Boolean) = sp(c).edit().putBoolean(K_AUTO_INSTALL, v).apply()

    // ---- 忽略清單（存「包名:版本碼」，換版本後會再次提醒）----
    fun ignored(c: Context): Set<String> = sp(c).getStringSet(K_IGNORED, null)?.toSet() ?: emptySet()
    fun setIgnored(c: Context, v: Set<String>) = sp(c).edit().putStringSet(K_IGNORED, v).apply()

    fun ignoreVersion(c: Context, packageName: String, versionCode: Long) {
        setIgnored(c, ignored(c) + "$packageName:$versionCode")
    }

    fun unignore(c: Context, packageName: String) {
        setIgnored(c, ignored(c).filterTo(HashSet()) { !it.startsWith("$packageName:") })
    }

    // ---- 上次檢查時間 ----
    fun lastCheck(c: Context): Long = sp(c).getLong(K_LAST_CHECK, 0L)
    fun setLastCheck(c: Context, v: Long) = sp(c).edit().putLong(K_LAST_CHECK, v).apply()

    // ---- 統一帳號 session（Google / GitHub 共用）----
    private const val K_ID = "account_id"
    private const val K_EMAIL = "account_email"
    private const val K_NAME = "account_name"
    private const val K_PROVIDER = "account_provider"
    private const val K_SIGNED_AT = "account_signed_at"

    /** 唯一身份：email，或 github:<login> */
    fun signedInId(c: Context): String? = sp(c).getString(K_ID, null)
    fun signedInEmailValue(c: Context): String? = sp(c).getString(K_EMAIL, null)
    fun signedInName(c: Context): String? = sp(c).getString(K_NAME, null)
    fun signedInProvider(c: Context): Provider =
        runCatching { Provider.valueOf(sp(c).getString(K_PROVIDER, null) ?: "") }
            .getOrDefault(Provider.GOOGLE)
    fun signedInAt(c: Context): Long = sp(c).getLong(K_SIGNED_AT, 0L)

    fun setAccount(c: Context, a: Account) {
        sp(c).edit()
            .putString(K_ID, a.id)
            .putString(K_EMAIL, a.email)
            .putString(K_NAME, a.displayName)
            .putString(K_PROVIDER, a.provider.name)
            .putLong(K_SIGNED_AT, a.signedInAt)
            .apply()
    }

    fun clearAccount(c: Context) {
        sp(c).edit()
            .remove(K_ID)
            .remove(K_EMAIL)
            .remove(K_NAME)
            .remove(K_PROVIDER)
            .remove(K_SIGNED_AT)
            .apply()
    }

    // ---- 存取碼分級（不需要 OAuth 的輕量授權）----
    private const val K_TIERS = "access_tiers"
    private const val K_TIER_LABEL = "access_label"

    /** 已解鎖的分級 key 集合 */
    fun accessTiers(c: Context): Set<String> =
        sp(c).getStringSet(K_TIERS, null)?.toSet() ?: emptySet()

    fun setAccessTiers(c: Context, v: Set<String>) =
        sp(c).edit().putStringSet(K_TIERS, v).apply()

    /** 顯示用的身分名稱 */
    fun accessLabel(c: Context): String = sp(c).getString(K_TIER_LABEL, null) ?: ""
    fun setAccessLabel(c: Context, v: String) =
        sp(c).edit().putString(K_TIER_LABEL, v).apply()

    // ---- 離線授權 License（RSA 簽名，無法偽造）----
    private const val K_LICENSE = "license_raw"
    private const val K_LIC_EMAIL = "license_email"
    private const val K_LIC_TIERS = "license_tiers"
    private const val K_LIC_EXP = "license_exp"

    fun licenseRaw(c: Context): String? = sp(c).getString(K_LICENSE, null)

    fun licenseClaims(c: Context): License.Claims? {
        if (sp(c).getString(K_LICENSE, null).isNullOrBlank()) return null
        val tiers = sp(c).getString(K_LIC_TIERS, null)
            ?.split(",")?.map { it.trim().lowercase() }?.filter { it.isNotBlank() }
            ?: return null
        return License.Claims(
            email = sp(c).getString(K_LIC_EMAIL, null) ?: "",
            tiers = tiers,
            issuedAt = 0L,
            expiresAt = sp(c).getLong(K_LIC_EXP, 0L),
            note = ""
        )
    }

    fun setLicense(c: Context, raw: String, claims: License.Claims) {
        sp(c).edit()
            .putString(K_LICENSE, raw)
            .putString(K_LIC_EMAIL, claims.email)
            .putString(K_LIC_TIERS, claims.tiers.joinToString(","))
            .putLong(K_LIC_EXP, claims.expiresAt)
            .apply()
    }

    fun clearLicense(c: Context) {
        sp(c).edit()
            .remove(K_LICENSE)
            .remove(K_LIC_EMAIL)
            .remove(K_LIC_TIERS)
            .remove(K_LIC_EXP)
            .apply()
    }
}
