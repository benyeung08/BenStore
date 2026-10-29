package com.benstore.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.util.Base64
import androidx.browser.customtabs.CustomTabsIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Google 帳號登入 —— **網頁（Chrome 自訂分頁）通道**。
 *
 * 為什麼需要這一條：官方 Credential Manager 在某些環境會失敗，例如
 *   - 裝置沒有裝 / 沒更新 Google Play 服務（中國大陸手機、客製 ROM、模擬器）
 *   - 使用者帳號不在系統帳號清單裡
 * 這條路只靠「瀏覽器 + Web Client ID」，完全不需要登記包名與 SHA-1，
 * 所以 CI 換簽章金鑰也不會失效。
 *
 * 流程：PKCE（RFC 7636）→ 開瀏覽器授權 → Google 導回本機 loopback 埠
 *      → 用授權碼換 id_token → 解析出 email / 名稱。
 */
object GoogleWebAuth {

    private const val AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"
    private const val TOKEN_URL = "https://oauth2.googleapis.com/token"

    /**
     * 候選 loopback 埠。
     * ⚠️ redirect URI 必須與 Google Cloud Console 裡登記的**完全一致**（含埠號），
     *    所以這裡用固定埠而不是隨機埠。請把你打算用的那一組登記進去：
     *      http://127.0.0.1:8765
     *      http://127.0.0.1:8766
     *      http://127.0.0.1:8767
     *    只登記 8765 也可以，只要那個埠沒被別的程式佔用。
     */
    private val PORTS = intArrayOf(8765, 8766, 8767)

    private const val SCOPES = "openid email profile"
    private const val TIMEOUT_MS = 180_000

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * @param activity     用來開瀏覽器的 Activity
     * @param clientId     Web 型別的 OAuth 用戶端 ID
     * @param clientSecret 選填。Web 型別用戶端換 token 時 Google 會要求 secret；
     *                     沒填就只用 PKCE（部分用戶端允許，失敗請回頭補上）
     */
    suspend fun signIn(
        activity: Activity,
        clientId: String,
        clientSecret: String = ""
    ): Result<Account> = withContext(Dispatchers.IO) {
        if (clientId.isBlank()) {
            return@withContext Result.failure(
                IllegalStateException("尚未設定 WEB_CLIENT_ID")
            )
        }

        val verifier = randomUrlSafe(64)
        val challenge = s256(verifier)

        // 挑一個能綁的埠
        val port = PORTS.firstOrNull { canBind(it) }
            ?: return@withContext Result.failure(
                IllegalStateException(
                    "loopback 埠 ${PORTS.joinToString("、")} 都被佔用，請關閉相關程式後重試"
                )
            )
        val redirectUri = "http://127.0.0.1:$port"

        // state 用來防止 CSRF：loopback 埠是任何本機程式都能連的，
        // 若回呼時不比對 state，惡意程式可以先連上埠餵一個授權碼進來。
        val state = randomUrlSafe(24)

        val authUri = Uri.parse(AUTH_URL).buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("redirect_uri", redirectUri)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("scope", SCOPES)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("state", state)
            .appendQueryParameter("access_type", "online")
            .appendQueryParameter("prompt", "select_account")
            .build()

        // 開瀏覽器：有 Chrome 就用自訂分頁（比較連貫），沒有就用一般瀏覽器
        withContext(Dispatchers.Main) {
            try {
                CustomTabsIntent.Builder().build().launchUrl(activity, authUri)
            } catch (t: Throwable) {
                activity.startActivity(
                    Intent(Intent.ACTION_VIEW, authUri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }

        // 等瀏覽器把授權碼導回來
        val (code, returnedState) = waitForRedirect(port)
        if (code == null) {
            return@withContext Result.failure(
                IllegalStateException("沒有收到授權碼（逾時，或使用者取消）")
            )
        }
        // state 必須與發出去的一致，否則視為偽造的回呼
        if (!returnedState.isNullOrBlank() && returnedState != state) {
            return@withContext Result.failure(
                IllegalStateException("登入中止：state 不匹配，疑似偽造的回呼請求")
            )
        }

        return@withContext try {
            val idToken = exchangeCode(code, verifier, redirectUri, clientId, clientSecret)
            val (email, name) = parseIdToken(idToken)
            val account = Account(
                id = email,
                email = email,
                displayName = name.ifBlank { email.substringBefore("@") },
                provider = Provider.GOOGLE,
                signedInAt = System.currentTimeMillis()
            )
            Prefs.setAccount(activity, account)
            Result.success(account)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    // ---------------------------------------------------------------- 換 token

    private fun exchangeCode(
        code: String,
        verifier: String,
        redirectUri: String,
        clientId: String,
        clientSecret: String
    ): String {
        val form = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("client_id", clientId)
            .add("redirect_uri", redirectUri)
            .add("code_verifier", verifier)
        if (clientSecret.isNotBlank()) form.add("client_secret", clientSecret)

        val request = Request.Builder().url(TOKEN_URL).post(form.build()).build()
        http.newCall(request).execute().use { response ->
            val text = (response.body ?: throw IllegalStateException("回應為空")).string()
            if (!response.isSuccessful) {
                val msg = runCatching { JSONObject(text).optString("error_description") }
                    .getOrNull().takeIf { !it.isNullOrBlank() } ?: text
                throw IllegalStateException("換 token 失敗：$msg")
            }
            val idToken = JSONObject(text).optString("id_token")
            if (idToken.isBlank()) throw IllegalStateException("回應裡沒有 id_token")
            return idToken
        }
    }

    // ---------------------------------------------------------------- 本機一次性伺服器

    private fun canBind(port: Int): Boolean = try {
        ServerSocket(port).use { true }
    } catch (t: Throwable) {
        false
    }

    /** 接住瀏覽器導回的授權碼，並回一個簡單頁面給瀏覽器顯示 */
    private fun waitForRedirect(port: Int): Pair<String?, String?> {
        val server = try {
            ServerSocket(port)
        } catch (t: Throwable) {
            return null to null
        }
        return try {
            server.soTimeout = TIMEOUT_MS
            val socket = server.accept()
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val requestLine = reader.readLine() ?: return (null to null)
                val path = requestLine.split(" ").getOrNull(1) ?: return (null to null)

                val html = "<html><body style='font-family:sans-serif;text-align:center;" +
                    "padding:48px'><h2>登入完成</h2><p>可以回到 BenStore 了</p></body></html>"
                val bytes = html.toByteArray(Charsets.UTF_8)
                val out: OutputStream = socket.getOutputStream()
                out.write(
                    (
                        "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: text/html; charset=utf-8\r\n" +
                            "Content-Length: ${bytes.size}\r\n" +
                            "Connection: close\r\n\r\n"
                        ).toByteArray(Charsets.US_ASCII)
                )
                out.write(bytes)
                out.flush()

                val uri = Uri.parse("http://127.0.0.1$path")
                uri.getQueryParameter("error")?.let {
                    throw IllegalStateException("Google 回傳錯誤：$it")
                }
                uri.getQueryParameter("code") to uri.getQueryParameter("state")
            } finally {
                runCatching { socket.close() }
            }
        } catch (t: Throwable) {
            if (t is IllegalStateException) throw t
            null to null
        } finally {
            runCatching { server.close() }
        }
    }

    // ---------------------------------------------------------------- 工具

    /** 只解 payload 拿 email / name；簽章由 Google 端保證，App 不驗簽（見 README） */
    private fun parseIdToken(idToken: String): Pair<String, String> {
        val parts = idToken.split(".")
        if (parts.size < 2) throw IllegalStateException("id_token 格式錯誤")
        val payload = JSONObject(decodeBase64Url(parts[1]))
        val email = payload.optString("email").trim().lowercase()
        if (email.isBlank()) throw IllegalStateException("id_token 裡沒有 email")
        return email to payload.optString("name")
    }

    private fun decodeBase64Url(s: String): String {
        val bytes = Base64.decode(s, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        return String(bytes, Charsets.UTF_8)
    }

    private fun randomUrlSafe(len: Int): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
        val rnd = SecureRandom()
        return (1..len).map { alphabet[rnd.nextInt(alphabet.length)] }.joinToString("")
    }

    private fun s256(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(
            digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
    }
}
