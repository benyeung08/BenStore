package com.benstore.app

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * GitHub token 與 client 設定。
 *
 * token 存 EncryptedSharedPreferences（AES-256-GCM，金鑰由系統 Keystore 保管），
 * 不是一般的 SharedPreferences —— token 等同於你 GitHub 帳號的部分權限，
 * 明文存在 /data/data 下，root 過的機器能直接讀走。
 */
object AuthSettings {

    /**
     * 關於 PKCE 的重要說明（為什麼預設走 Device Flow）：
     *
     * GitHub 官方文件明確寫著：
     *   "Client secrets are required to generate user access tokens for your app,
     *    unless your app uses the device flow."
     *
     * 也就是說 GitHub 支援 PKCE，但 PKCE **不會**免除 client_secret ——
     * token 端點仍然要求它。行動 App 藏不住 secret，只能把 secret 包進 APK，
     * 而任何人都反編譯得出來。PKCE 在這裡的角色是「即使 secret 洩了，
     * 被攔截的 authorization code 也換不到 token」，是加層防護而非替代。
     *
     * Device Flow 則是 GitHub 官方唯一真正免 secret 的流程，
     * client ID 天生就是公開值（它自己什麼都開不了，只能發起一次授權）。
     *
     * 代價：Device Flow 要使用者手動輸入一組短碼；
     * 且 GitHub 提醒「public client 可被冒用」——任何人都能拿你的 client ID
     * 發起授權。所以**不要**用「有沒有 token」當作你服務的門禁，
     * 那擋不住人；要擋就擋在私有倉庫權限上（repo 有權限才讀得到）。
     */
    const val CONFIG_NOTE = "github_pkce_secret_note"

    private const val FILE = "benstore_auth"
    private const val K_TOKEN = "gh_token"
    private const val K_LOGIN = "gh_login"
    private const val K_NAME = "gh_name"
    private const val K_AVATAR = "gh_avatar"
    private const val K_VERIFIER = "pkce_verifier"

    private const val PUB_FILE = "benstore_auth_cfg"
    private const val K_CLIENT_ID = "client_id"
    private const val K_SCOPE = "scope"
    private const val K_SECRET = "client_secret"

    @Volatile
    private var enc: SharedPreferences? = null

    private fun prefs(context: Context): SharedPreferences {
        enc?.let { return it }
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        val p = EncryptedSharedPreferences.create(
            context,
            FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        enc = p
        return p
    }

    private fun cfg(context: Context): SharedPreferences =
        context.getSharedPreferences(PUB_FILE, Context.MODE_PRIVATE)

    // ---- token ----
    fun token(context: Context): String = prefs(context).getString(K_TOKEN, null) ?: ""
    fun isLoggedIn(context: Context): Boolean = token(context).isNotBlank()

    fun saveSession(context: Context, token: String, user: GitHubUser) {
        prefs(context).edit()
            .putString(K_TOKEN, token)
            .putString(K_LOGIN, user.login)
            .putString(K_NAME, user.name)
            .putString(K_AVATAR, user.avatarUrl)
            .apply()
    }

    fun clearSession(context: Context) {
        prefs(context).edit()
            .remove(K_TOKEN)
            .remove(K_LOGIN)
            .remove(K_NAME)
            .remove(K_AVATAR)
            .apply()
    }

    fun currentUser(context: Context): GitHubUser? {
        val login = prefs(context).getString(K_LOGIN, null) ?: return null
        return GitHubUser(
            login = login,
            name = prefs(context).getString(K_NAME, "") ?: "",
            avatarUrl = prefs(context).getString(K_AVATAR, "") ?: ""
        )
    }

    // ---- 可變設定（不用重新編譯就能換）----
    fun clientId(context: Context): String =
        cfg(context).getString(K_CLIENT_ID, null)?.takeIf { it.isNotBlank() }
            ?: GitHubConfig.DEFAULT_CLIENT_ID

    fun setClientId(context: Context, v: String) =
        cfg(context).edit().putString(K_CLIENT_ID, v.trim()).apply()

    fun scope(context: Context): String =
        cfg(context).getString(K_SCOPE, null)?.takeIf { it.isNotBlank() }
            ?: GitHubConfig.DEFAULT_SCOPE

    fun setScope(context: Context, v: String) =
        cfg(context).edit().putString(K_SCOPE, v.trim()).apply()

    /** 只有 Web Flow + PKCE 才需要；Device Flow 留空即可 */
    fun clientSecret(context: Context): String =
        cfg(context).getString(K_SECRET, null) ?: ""

    fun setClientSecret(context: Context, v: String) =
        cfg(context).edit().putString(K_SECRET, v.trim()).apply()

    /**
     * Web Flow 用：PKCE verifier 只活在一次登入流程裡，
     * 優先用記憶體，進程被殺時退回加密偏好設定。
     */
    @Volatile
    var lastVerifier: String = ""
        get() {
            if (field.isNotBlank()) return field
            return verifierPrefs?.getString(K_VERIFIER, null) ?: ""
        }
        set(value) {
            field = value
            verifierPrefs?.edit()?.putString(K_VERIFIER, value)?.apply()
        }

    @Volatile
    private var verifierPrefs: SharedPreferences? = null

    fun bindVerifierStore(context: Context) { verifierPrefs = prefs(context) }

    fun clearVerifier(context: Context) {
        lastVerifier = ""
        prefs(context).edit().remove(K_VERIFIER).apply()
    }

    fun isConfigured(context: Context): Boolean {
        val id = clientId(context)
        return id.isNotBlank() && !id.contains("YOUR_CLIENT_ID")
    }
}
