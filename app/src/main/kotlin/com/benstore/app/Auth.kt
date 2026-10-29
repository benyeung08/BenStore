package com.benstore.app

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 登入來源 */
enum class Provider(val label: String) {
    GOOGLE("Google"),
    GITHUB("GitHub"),
    /** 純本機的商店帳號：帳號 + 密碼，存在裝置上，不連任何伺服器 */
    LOCAL("BenStore 帳號")
}

/**
 * 統一帳號：不管用 Google 還是 GitHub 登入，都收斂成這個物件。
 *
 * 身份依據統一用 **email**：
 *   - Google：直接拿得到
 *   - GitHub：打 /user/emails 拿主要信箱（需要 user:email scope）
 *     拿不到時退回 `github:<login>`，門禁與分級改以帳號名比對
 */
data class Account(
    val id: String,          // 唯一身份：email，或 github:<login>
    val email: String,       // 可能為空（GitHub 未公開信箱時）
    val displayName: String,
    val provider: Provider,
    val signedInAt: Long
) {
    /** 顯示用：有名稱用名稱，否則 email，再否則 id */
    fun title(): String = displayName.ifBlank { email.ifBlank { id } }
}

object Auth {

    private const val TAG = "BenStore.Auth"

    /**
     * 公開模式：任何人都能用，不用登入，全部應用都顯示。
     * 為 true 時下面的門禁與分級全部失效。
     */
    fun isPublic(): Boolean = BuildConfig.PUBLIC_MODE

    /** Google 登入是否可用（有填 Web Client ID 才算） */
    fun googleConfigured(): Boolean = BuildConfig.WEB_CLIENT_ID.isNotBlank()

    /** GitHub 登入是否可用（有填 Client ID 才算） */
    fun githubConfigured(context: Context): Boolean =
        AuthSettings.isConfigured(context)

    /** 本機商店帳號是否可用（裝置上已存在至少一組帳號） */
    fun localConfigured(context: Context): Boolean = LocalAuth.hasAccounts(context)

    /** 門禁是否成立：公開模式下永遠是 false（不擋任何人） */
    fun gateEnabled(context: Context): Boolean =
        !isPublic() && (googleConfigured() || githubConfigured(context) || localConfigured(context))

    /** 是否依帳號分級顯示：公開模式下不分級，全部顯示 */
    fun tieringEnabled(): Boolean = !isPublic()

    /** 允許使用的帳號清單；空白代表不限 */
    fun allowedEmails(): List<String> =
        BuildConfig.ALLOWED_EMAILS.split(",")
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }

    fun current(context: Context): Account? {
        val id = Prefs.signedInId(context) ?: return null
        return Account(
            id = id,
            email = Prefs.signedInEmailValue(context) ?: if (id.contains("@")) id else "",
            displayName = Prefs.signedInName(context) ?: id.substringBefore("@"),
            provider = Prefs.signedInProvider(context),
            signedInAt = Prefs.signedInAt(context)
        )
    }

    fun isSignedIn(context: Context): Boolean = Prefs.signedInId(context) != null

    /**
     * 門禁：已登入且通過允許清單。
     * 公開模式下一律放行。
     */
    fun isAuthorized(context: Context): Boolean {
        if (isPublic()) return true
        val a = current(context) ?: return false
        val allowed = allowedEmails()
        return allowed.isEmpty() || allowed.any { matches(it, a) }
    }

    /** 名單項目是否命中這個帳號 */
    private fun matches(rule: String, a: Account): Boolean {
        val r = rule.trim().lowercase()
        if (r == a.id.lowercase()) return true
        if (a.email.isNotBlank() && r == a.email.lowercase()) return true
        // 允許用「github:login」或純 login 來指定
        if (a.provider == Provider.GITHUB) {
            val login = a.id.removePrefix("github:")
            if (r == "github:$login" || r == login) return true
        }
        // 本機帳號：允許寫「local:名稱」或直接寫帳號名稱
        if (a.provider == Provider.LOCAL) {
            val name = a.id.removePrefix("local:")
            if (r == "local:$name" || r == name) return true
        }
        return false
    }

    /** 給分級規則用：某個應用是否對這個帳號可見 */
    fun canSee(allowed: List<String>, context: Context): Boolean {
        if (allowed.isEmpty()) return true
        val a = current(context) ?: return false
        return allowed.any { matches(it, a) }
    }

    // ---------------------------------------------------------------- Google

    /**
     * Google 登入。成功回傳 Account。
     *
     * **兩條通道，自動擇一**：
     *  1. 先試官方 Credential Manager（體驗最好，會跳出系統帳號選擇器）
     *  2. 失敗就自動降級到 `GoogleWebAuth`（Chrome 自訂分頁的網頁 OAuth）
     *
     * 兩條都傳同一個「網頁應用程式」型別的 Client ID。
     * 差別：官方通道還需要在 GCP 建一個「Android」型別用戶端（包名 + SHA-1）
     * 才能通過驗證；網頁通道不需要，所以沒有 SHA-1 / 換簽章金鑰時就靠它。
     * 要能降級，請傳 Activity context（開瀏覽器需要）。
     */
    suspend fun signInWithGoogle(activityContext: Context): Result<Account> =
        withContext(Dispatchers.Main) {
            if (!googleConfigured()) {
                return@withContext Result.failure(
                    IllegalStateException("尚未設定 WEB_CLIENT_ID，請見 build.gradle.kts")
                )
            }
            val credentialManager = CredentialManager.create(activityContext)
            try {
                val googleIdOption = GetGoogleIdOption.Builder()
                    .setFilterByAuthorizedAccounts(false)
                    .setServerClientId(BuildConfig.WEB_CLIENT_ID)
                    .setAutoSelectEnabled(true)
                    .build()

                val request = GetCredentialRequest.Builder()
                    .addCredentialOption(googleIdOption)
                    .build()

                val result = credentialManager.getCredential(
                    context = activityContext,
                    request = request
                )
                val credential = result.credential

                if (credential is CustomCredential &&
                    credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                ) {
                    val gid = GoogleIdTokenCredential.createFrom(credential.data)
                    // 官方文件：GoogleIdTokenCredential.id 就是使用者的 email
                    val email = gid.id.trim().lowercase()
                    if (email.isBlank()) {
                        return@withContext Result.failure(
                            IllegalStateException("取得的帳號沒有 email")
                        )
                    }
                    val name = gid.displayName?.takeIf { it.isNotBlank() }
                        ?: email.substringBefore("@")

                    val account = Account(
                        id = email,
                        email = email,
                        displayName = name,
                        provider = Provider.GOOGLE,
                        signedInAt = System.currentTimeMillis()
                    )
                    Prefs.setAccount(activityContext, account)
                    Log.d(TAG, "google signed in as $email")
                    Result.success(account)
                } else {
                    Result.failure(
                        IllegalStateException("非預期的憑證類型：${credential.type}")
                    )
                }
            } catch (e: GetCredentialException) {
                Log.w(TAG, "getCredential 失敗: ${e.message}，改走網頁登入")
                fallbackToWebGoogle(activityContext, friendlyError(e))
            } catch (t: Throwable) {
                Log.w(TAG, "google 登入失敗，改走網頁登入", t)
                fallbackToWebGoogle(activityContext, t.message)
            }
        }

    /** Credential Manager 失敗時的降級：開瀏覽器做網頁 OAuth */
    private suspend fun fallbackToWebGoogle(
        activityContext: Context,
        reason: String?
    ): Result<Account> {
        val activity = findActivity(activityContext)
            ?: return Result.failure(
                IllegalStateException(
                    "官方登入失敗（$reason），且拿不到 Activity 無法改用網頁登入"
                )
            )
        Log.d(TAG, "fallback to web google: $reason")
        return GoogleWebAuth.signIn(
            activity = activity,
            clientId = BuildConfig.WEB_CLIENT_ID,
            clientSecret = BuildConfig.GOOGLE_CLIENT_SECRET
        )
    }

    /** 從 Compose 的 LocalContext 一路拆 ContextWrapper 找出 Activity */
    private fun findActivity(context: Context): Activity? {
        var c: Context? = context
        while (c != null) {
            if (c is Activity) return c
            c = (c as? android.content.ContextWrapper)?.baseContext
        }
        return null
    }

    // ---------------------------------------------------------------- GitHub

    /**
     * GitHub 登入（Device Flow）。
     *
     * @param dc      已取得的裝置碼
     * @param poll    執行輪詢的 suspend 函式，回傳 access token
     * 流程拆開是因為 UI 需要先顯示 user code 給使用者。
     */
    suspend fun signInWithGitHub(
        context: Context,
        poll: suspend () -> String
    ): Result<Account> = withContext(Dispatchers.IO) {
        try {
            val token = poll()
            val user = GitHubAuth.fetchUser(token)
            val email = GitHubAuth.fetchPrimaryEmail(token)
                ?: user.email?.trim()?.lowercase()

            val id = if (!email.isNullOrBlank()) email else "github:${user.login.lowercase()}"
            val name = user.name.takeIf { it.isNotBlank() } ?: user.login

            val account = Account(
                id = id,
                email = email ?: "",
                displayName = name,
                provider = Provider.GITHUB,
                signedInAt = System.currentTimeMillis()
            )
            // token 另外存（抓私有 repo 用），這裡只存帳號身份
            AuthSettings.saveSession(context, token, user)
            Prefs.setAccount(context, account)
            Log.d(TAG, "github signed in as $id")
            Result.success(account)
        } catch (t: Throwable) {
            Log.w(TAG, "github 登入失敗", t)
            Result.failure(t)
        }
    }

    /**
     * 直接貼 Personal Access Token（PAT）登入。
     *
     * 這是「完全不用建 OAuth App」的 GitHub 方案：
     * 使用者自己去 GitHub → Settings → Developer settings →
     * Personal access tokens 產生一組 token 貼進來。
     * 你這邊什麼都不用申請、不用建 Client ID、不用 secret。
     *
     * 代價：要使用者自己產生 PAT，步驟比 Device Flow 多一點。
     * 好處：零建設，且同一個 token 就能讀私有 repo。
     */
    suspend fun signInWithToken(context: Context, tokenInput: String): Result<Account> =
        withContext(Dispatchers.IO) {
            val token = tokenInput.trim()
            if (token.isBlank()) {
                return@withContext Result.failure(IllegalStateException("請貼上 token"))
            }
            try {
                val user = GitHubAuth.fetchUser(token)
                val email = GitHubAuth.fetchPrimaryEmail(token)
                    ?: user.email?.trim()?.lowercase()
                val id = if (!email.isNullOrBlank()) email else "github:${user.login.lowercase()}"
                val name = user.name.takeIf { it.isNotBlank() } ?: user.login

                val account = Account(
                    id = id,
                    email = email ?: "",
                    displayName = name,
                    provider = Provider.GITHUB,
                    signedInAt = System.currentTimeMillis()
                )
                AuthSettings.saveSession(context, token, user)
                Prefs.setAccount(context, account)
                Log.d(TAG, "github (PAT) signed in as $id")
                Result.success(account)
            } catch (t: Throwable) {
                Log.w(TAG, "PAT 登入失敗", t)
                Result.failure(
                    IllegalStateException(
                        if (t.message?.contains("401", true) == true)
                            "token 無效或已過期，請確認有勾選 read:user / user:email 權限"
                        else "登入失敗：${t.message}"
                    )
                )
            }
        }

    // ---------------------------------------------------------------- 登出

    /** 登出：清掉 Google 憑證狀態、嘗試撤銷 GitHub token、清除本機 session */
    suspend fun signOut(context: Context) = withContext(Dispatchers.Main) {
        val a = current(context)
        try {
            CredentialManager.create(context)
                .clearCredentialState(ClearCredentialStateRequest())
        } catch (t: Throwable) {
            Log.w(TAG, "clearCredentialState 失敗", t)
        }
        // GitHub token 能撤銷就撤銷；Device Flow 沒有 secret 時只會清本機
        val token = AuthSettings.token(context)
        if (!token.isNullOrBlank()) {
            withContext(Dispatchers.IO) {
                runCatching {
                    GitHubAuth.revokeToken(
                        AuthSettings.clientId(context),
                        AuthSettings.clientSecret(context),
                        token
                    )
                }
            }
        }
        AuthSettings.clearSession(context)
        Prefs.clearAccount(context)
        Log.d(TAG, "signed out (was ${a?.id})")
    }

    private fun friendlyError(e: GetCredentialException): String {
        val m = e.message.orEmpty()
        return when {
            m.contains("cancel", true) -> "已取消登入"
            m.contains("No credentials", true) || m.contains("TYPE_NO_CREDENTIAL", true) ->
                "裝置上找不到可用的 Google 帳號，或 Web Client ID 設定有誤"
            m.contains("network", true) -> "網路連線失敗"
            m.contains("DEVELOPER_ERROR", true) || m.contains("not set up", true) ->
                "Google Cloud Console 設定有誤：請確認用的是「網頁應用程式」型別的 Client ID"
            // [16] Account reauth failed：多半是沒建 Android 型別用戶端，或 SHA-1 與簽章不符
            m.contains("[16]", true) || m.contains("reauth", true) ->
                "官方通道驗證失敗（通常是缺少 Android 型別用戶端的包名 + SHA-1）"
            else -> "登入失敗：${e.type} ${m}"
        }
    }
}

fun isVisibleTo(app: RepoApp, account: Account?): Boolean =
    isVisibleTo(app, account, emptySet())

/**
 * 分級規則：決定目前這個使用者看不看得到某個應用。
 *
 * 兩套機制並存，任一通過即可看見：
 *
 *  1. **存取碼分級**（主要，零設定）
 *     應用寫 `requiredTiers`，使用者輸入存取碼解鎖對應分級。
 *     不需要 OAuth、不需要後端、不需要 Client ID。
 *
 *  2. **帳號分級**（選用，需要 Google / GitHub 登入）
 *     應用寫 `allowedEmails`，命中目前登入帳號才可見。
 *
 * 公開模式下一律可見；兩個欄位都沒寫也一律可見。
 */
fun isVisibleTo(
    app: RepoApp,
    account: Account?,
    grantedTiers: Set<String>
): Boolean {
    if (Auth.isPublic()) return true

    // 沒設任何限制 → 所有人可見
    if (app.requiredTiers.isEmpty() && app.allowedEmails.isEmpty()) return true

    // 1. 存取碼分級
    if (app.requiredTiers.isNotEmpty()) {
        val g = grantedTiers.map { it.trim().lowercase() }.toSet()
        if (app.requiredTiers.any { it.trim().lowercase() in g }) return true
    }

    // 2. 帳號分級
    if (app.allowedEmails.isNotEmpty()) {
        val a = account ?: return false
        if (app.allowedEmails.any { rule ->
                val r = rule.trim().lowercase()
                r == a.id.lowercase() ||
                    (a.email.isNotBlank() && r == a.email.lowercase()) ||
                    (a.provider == Provider.GITHUB &&
                        (r == a.id.removePrefix("github:") ||
                            r == "github:${a.id.removePrefix("github:")}"))
            }) return true
    }
    return false
}
