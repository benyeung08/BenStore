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

    // 降級標誌必須「持久化」：原本只用行程內的 @Volatile 變數，
    // 重開 App 後又去試加密檔 → Keystore 時好時壞的裝置會在
    // 加密檔與 plain 檔之間來回切，token 一下讀得到、一下讀不到，
    // 使用者會莫名其妙被登出。一旦降級就固定走 plain，行為才確定。
    private const val STATE_FILE = "benstore_keystore_state"
    private const val K_DEGRADED = "auth_degraded"

    private fun degraded(context: Context): Boolean =
        context.getSharedPreferences(STATE_FILE, Context.MODE_PRIVATE)
            .getBoolean(K_DEGRADED, false)

    private fun markDegraded(context: Context) =
        context.getSharedPreferences(STATE_FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(K_DEGRADED, true).apply()

    /**
     * 部分裝置（改過的 ROM、模擬器、Keystore 損壞時）建立
     * EncryptedSharedPreferences 會直接拋例外。失敗就退回一般
     * SharedPreferences：token 變成明文儲存，安全性下降，但功能還能用。
     *
     * 注意兩個檔案不能同名：加密檔的 key/value 都是密文，
     * 用同一個檔名開成普通 prefs 會讀到亂碼並寫壞資料，所以必須分開。
     */
    private fun prefs(context: Context): SharedPreferences {
        enc?.let { return it }
        var p: SharedPreferences? = null
        if (!degraded(context)) {
            p = try {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    context,
                    FILE,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (t: Throwable) {
                android.util.Log.w(
                    "BenStore.AuthSettings",
                    "Keystore 不可用，token 改以明文保存（已永久降級，清除 App 資料可重試）",
                    t
                )
                markDegraded(context)
                null
            }
        }
        if (p == null) p = context.getSharedPreferences(FILE + "_plain", Context.MODE_PRIVATE)
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
