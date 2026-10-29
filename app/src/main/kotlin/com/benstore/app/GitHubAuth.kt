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

    /** 輪詢直到使用者完成授權，回傳 access token */
    suspend fun pollForToken(
        clientId: String,
        dc: DeviceCode,
        onTick: () -> Unit = {}
    ): String = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + dc.expiresIn * 1000L
        var wait = dc.interval.coerceAtLeast(5).toLong()
        while (System.currentTimeMillis() < deadline) {
            delay(wait * 1000L)
            onTick()
            val token = tryPoll(clientId, dc, wait)
            if (token.first != null) return@withContext token.first!!
            wait = token.second
        }
        error("授權逾時，請重新嘗試")
    }

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
            val j = JSONObject(text)
            if (j.has("access_token")) return j.getString("access_token") to wait
            return when (j.optString("error")) {
                "authorization_pending" -> null to wait
                "slow_down" -> null to (wait + 5)
                else -> error(
                    j.optString("error_description")
                        .ifBlank { j.optString("error", "授權失敗") }
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
            val j = JSONObject(resp.body!!.string())
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
                val arr = org.json.JSONArray(resp.body!!.string())
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
