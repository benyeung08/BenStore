package com.benstore.app

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * GitHub 登入。提供兩條路：
 *
 * 1. Device Flow（預設，推薦）—— 官方唯一「不需要 client secret」的流程。
 *    client ID 是公開值，密碼學上無密可洩。適合沒有後端的行動 App。
 *
 * 2. Web Flow + PKCE —— 體驗較好（瀏覽器跳轉、不用手動輸入碼），
 *    但 GitHub 的 OAuth App token 端點**仍然要求 client_secret**，
 *    所以行動端必須把 secret 包進 APK（可被反編譯取出）。PKCE 只是加一層防護，
 *    並沒有免除 secret。詳見 AuthSettings.kt 的 CONFIG_NOTE。
 */
object GitHubConfig {

    /** 改成你自己註冊的 OAuth App client ID（Device Flow 的 ID 可公開） */
    const val DEFAULT_CLIENT_ID = "Iv1.YOUR_CLIENT_ID_HERE"

    /**
     * repo 才能讀私有倉庫的 release 資產；只要公開內容就用 read:user。
     * user:email 是拿 email 用的 —— 統一帳號體系要靠 email 當身份，
     * 沒有這個 scope 就只能用 login（使用者名稱），跟 Google 帳號對不起來。
     */
    const val DEFAULT_SCOPE = "repo user:email"

    const val REDIRECT_URI = "benstore://oauth"
}

data class GitHubUser(
    val login: String,
    val name: String,
    val avatarUrl: String,
    val email: String? = null
)

data class DeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val expiresIn: Int,
    val interval: Int
)

data class PkcePair(val verifier: String, val challenge: String, val state: String)

object GitHubAuth {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private fun base64Url(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    fun newPkce(): PkcePair {
        val rnd = ByteArray(32)
        SecureRandom().nextBytes(rnd)
        val verifier = base64Url(rnd)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(Charsets.US_ASCII))
        return PkcePair(verifier, base64Url(digest), base64Url(ByteArray(16).also {
            SecureRandom().nextBytes(it)
        }))
    }

    // ------------------------------------------------------------------
    // 1. Device Flow —— 不需要 client secret
    // ------------------------------------------------------------------

    suspend fun requestDeviceCode(
        clientId: String,
        scope: String
    ): DeviceCode = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("client_id", clientId)
            .add("scope", scope)
            .build()
        val req = Request.Builder()
            .url("https://github.com/login/device/code")
            .post(body)
            .header("Accept", "application/json")
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: error("回應為空")
            if (!resp.isSuccessful) {
                val msg = runCatching { JSONObject(text).optString("error_description") }
                    .getOrNull()?.takeIf { it.isNotBlank() } ?: "HTTP ${resp.code}"
                error("取得裝置碼失敗：$msg")
            }
            val j = JSONObject(text)
            DeviceCode(
                deviceCode = j.getString("device_code"),
                userCode = j.getString("user_code"),
                verificationUri = j.optString("verification_uri", "https://github.com/login/device"),
                expiresIn = j.optInt("expires_in", 900),
                interval = j.optInt("interval", 5)
            )
        }
    }

    /**
     * 輪詢直到使用者完成授權，回傳 access token。
     *
     * 單次網路抖動 / GitHub 回非 JSON（維運頁、HTML 錯誤頁）不該中斷整個
     * 15 分鐘的授權流程：原本 tryPoll 裡 JSONObject(text) 一拋例外就往上炸，
     * 使用者得重新產生裝置碼。這裡把「可重試」與「明確失敗」分開。
     */
    suspend fun pollForToken(
        clientId: String,
        dc: DeviceCode,
        onTick: () -> Unit = {}
    ): String = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + dc.expiresIn * 1000L
        var wait = dc.interval.coerceAtLeast(5).toLong()
        var transient = 0
        while (System.currentTimeMillis() < deadline) {
            delay(wait * 1000L)
            onTick()
            val r = runCatching { tryPoll(clientId, dc, wait) }
            if (r.isSuccess) {
                val (token, next) = r.getOrThrow()
                if (token != null) return@withContext token
                wait = next
                transient = 0
            } else {
                val e = r.exceptionOrNull()
                if (e is kotlinx.coroutines.CancellationException) throw e
                // 明確的授權錯誤（expired_token / access_denied / 不支援的 grant）
                // 不該一直重試；其餘視為暫時性，最多連續容忍 5 次。
                if (e is TerminalAuthException) throw IllegalStateException(e.message)
                transient++
                if (transient >= 5) {
                    throw IllegalStateException(
                        "連續 $transient 次無法連上 GitHub，請檢查網路後重試", e
                    )
                }
            }
        }
        error("授權逾時，請重新嘗試")
    }

    /** 標記「明確失敗、重試也沒用」的授權錯誤 */
    private class TerminalAuthException(message: String) : IllegalStateException(message)

    /** 回傳 (token?, 下次等待秒數)；pending 時 token 為 null */
    private fun tryPoll(clientId: String, dc: DeviceCode, wait: Long): Pair<String?, Long> {
        val body = FormBody.Builder()
            .add("client_id", clientId)
            .add("device_code", dc.deviceCode)
            .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
            .build()
        val req = Request.Builder()
            .url("https://github.com/login/oauth/access_token")
            .post(body)
            .header("Accept", "application/json")
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: return null to wait
            // 維護頁 / proxy 錯誤頁可能是 HTML，直接 JSONObject() 會拋 JSONException
            val j = runCatching { JSONObject(text) }.getOrNull()
                ?: return null to wait
            if (j.has("access_token")) return j.getString("access_token") to wait
            return when (val err = j.optString("error")) {
                "authorization_pending" -> null to wait
                "slow_down" -> null to (wait + 5)
                else -> throw TerminalAuthException(
                    j.optString("error_description")
                        .ifBlank { j.optString("error", "授權失敗") }
                        .ifBlank { "GitHub 回應錯誤：$err" }
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // 2. Web Flow + PKCE —— 需要 client_secret（GitHub 的硬性要求）
    // ------------------------------------------------------------------

    fun authorizeUrl(clientId: String, scope: String, pkce: PkcePair): String =
        "https://github.com/login/oauth/authorize" +
            "?client_id=$clientId" +
            "&redirect_uri=${GitHubConfig.REDIRECT_URI}" +
            "&scope=${scope.replace(" ", "+")}" +
            "&state=${pkce.state}" +
            "&code_challenge=${pkce.challenge}" +
            "&code_challenge_method=S256"

    suspend fun exchangeCode(
        clientId: String,
        clientSecret: String,
        code: String,
        verifier: String
    ): String = withContext(Dispatchers.IO) {
        val j = JSONObject()
        j.put("client_id", clientId)
        j.put("client_secret", clientSecret)
        j.put("code", code)
        j.put("redirect_uri", GitHubConfig.REDIRECT_URI)
        j.put("code_verifier", verifier)
        val req = Request.Builder()
            .url("https://github.com/login/oauth/access_token")
            .post(j.toString().toJsonBody())
            .header("Accept", "application/json")
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: error("回應為空")
            val o = JSONObject(text)
            if (o.has("access_token")) return@withContext o.getString("access_token")
            error(o.optString("error_description").ifBlank { o.optString("error", "換 token 失敗") })
        }
    }

    // ------------------------------------------------------------------
    // 共用
    // ------------------------------------------------------------------

    suspend fun fetchUser(token: String): GitHubUser = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://api.github.com/user")
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("取得使用者資料失敗 HTTP ${resp.code}")
            val j = JSONObject(resp.body?.string() ?: error("回應為空"))
            GitHubUser(
                login = j.optString("login"),
                name = j.optString("name"),
                avatarUrl = j.optString("avatar_url"),
                email = j.optString("email").takeIf { it.isNotBlank() }
            )
        }
    }

    /**
     * 取得主要 email（統一帳號體系的身份依據）。
     *
     * GET /user 的 email 欄位只有在「使用者設為公開」時才有值，
     * 所以還是要打 /user/emails（需要 user:email scope）才可靠。
     * 取不到時回傳 null —— 這時登入仍然成功，只是沒有 email 可當身份，
     * 門禁與分級會退化成「只看 GitHub login」。
     */
    suspend fun fetchPrimaryEmail(token: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("https://api.github.com/user/emails")
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val arr = org.json.JSONArray(resp.body?.string() ?: return@withContext null)
                var fallback: String? = null
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val e = o.optString("email").trim()
                    if (e.isBlank()) continue
                    if (o.optBoolean("primary")) return@withContext e.lowercase()
                    if (o.optBoolean("verified") && fallback == null) fallback = e.lowercase()
                }
                fallback
            }
        }.getOrNull()
    }

    /**
     * 嘗試撤銷 token。
     *
     * 注意：GitHub 的撤銷端點 `DELETE /applications/{client_id}/token`
     * 需要 Basic 認證（client_id:client_secret）。Device Flow 沒有 secret，
     * 所以這裡只能嘗試；失敗是正常的，不代表本地登出失敗。
     * 使用者仍可到 GitHub → Settings → Applications → Authorized OAuth Apps 手動撤銷。
     */
    suspend fun revokeToken(clientId: String, secret: String, token: String): Boolean =
        withContext(Dispatchers.IO) {
            if (secret.isBlank() || token.isBlank()) return@withContext false
            runCatching {
                val basic = Base64.encodeToString(
                    "$clientId:$secret".toByteArray(), Base64.NO_WRAP
                )
                val req = Request.Builder()
                    .url("https://api.github.com/applications/$clientId/token")
                    .delete(JSONObject().apply { put("access_token", token) }.toString().toJsonBody())
                    .header("Accept", "application/vnd.github+json")
                    .header("Authorization", "Basic $basic")
                    .build()
                client.newCall(req).execute().use { it.isSuccessful }
            }.getOrDefault(false)
        }
}

private fun String.toJsonBody(): okhttp3.RequestBody =
    okhttp3.RequestBody.create(JSON_MEDIA, this)

private val JSON_MEDIA: okhttp3.MediaType by lazy {
    okhttp3.MediaType.get("application/json; charset=utf-8")
}
