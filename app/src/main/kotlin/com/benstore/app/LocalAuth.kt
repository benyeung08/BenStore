package com.benstore.app

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** 不含任何機密，可安全顯示在清單上 */
data class LocalAccount(
    val username: String,
    val displayName: String,
    val createdAt: Long,
    val lastLoginAt: Long
)

/**
 * 純本機的「商店帳號」：帳號 + 密碼，全部存在這支手機上，不連任何伺服器。
 *
 * 與 Google / GitHub 登入並列為第三種 Provider，登入後一樣寫進 `Prefs.setAccount`，
 * 所以門禁（`ALLOWED_EMAILS`）、分級顯示（tiers）、離線授權（License）全部照常運作。
 *
 * 密碼處理：PBKDF2WithHmacSHA256，12 萬次迭代 + 每組帳號獨立的隨機鹽，
 * 只存雜湊不存明文，比對用 `MessageDigest.isEqual` 常數時間比較。
 * 鹽與雜湊再存進 EncryptedSharedPreferences（Keystore 保管金鑰）；
 * 若裝置不支援加密偏好設定，自動退回一般 SharedPreferences——
 * 此時洩漏的是「雜湊值」而非密碼本身，風險仍可接受。
 *
 * 限制：這是單機帳號，換手機不會同步，也沒有「忘記密碼」的救援機制
 * （沒有伺服器可以寄信）。忘了密碼只能刪除該帳號重設，帳號內資料會一併清除。
 */
object LocalAuth {

    private const val FILE = "benstore_local_accounts"
    private const val PLAIN_FILE = "benstore_local_accounts_plain"
    private const val K_JSON = "accounts"

    private const val ITERATIONS = 120_000
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16
    const val MIN_PASSWORD = 6

    private val USERNAME_RE = Regex("^[a-z0-9_.]{3,20}$")

    @Volatile
    private var cache: SharedPreferences? = null

    @Volatile
    private var encFailed = false

    private fun prefs(context: Context): SharedPreferences {
        cache?.let { return it }
        var p: SharedPreferences? = null
        if (!encFailed) {
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
                encFailed = true
                null
            }
        }
        if (p == null) p = context.getSharedPreferences(PLAIN_FILE, Context.MODE_PRIVATE)
        cache = p
        return p
    }

    // ------------------------------------------------------------ 密碼學

    private fun derive(password: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_BITS)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    private fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)

    private fun verify(password: String, salt: String, hash: String): Boolean {
        val actual = try {
            derive(password, unb64(salt))
        } catch (t: Throwable) {
            return false
        }
        val expected = try {
            unb64(hash)
        } catch (t: Throwable) {
            return false
        }
        return MessageDigest.isEqual(actual, expected)
    }

    // ------------------------------------------------------------ 儲存

    private fun raw(context: Context): JSONArray =
        runCatching { JSONArray(prefs(context).getString(K_JSON, null) ?: "[]") }
            .getOrDefault(JSONArray())

    private fun save(context: Context, arr: JSONArray) =
        prefs(context).edit().putString(K_JSON, arr.toString()).apply()

    private fun find(arr: JSONArray, username: String): JSONObject? {
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("username") == username) return o
        }
        return null
    }

    fun list(context: Context): List<LocalAccount> {
        val arr = raw(context)
        val out = ArrayList<LocalAccount>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val u = o.optString("username")
            if (u.isBlank()) continue
            out.add(
                LocalAccount(
                    username = u,
                    displayName = o.optString("displayName").ifBlank { u },
                    createdAt = o.optLong("createdAt", 0L),
                    lastLoginAt = o.optLong("lastLoginAt", 0L)
                )
            )
        }
        return out.sortedByDescending { it.lastLoginAt }
    }

    fun count(context: Context): Int = list(context).size
    fun hasAccounts(context: Context): Boolean = list(context).isNotEmpty()
    fun exists(context: Context, username: String): Boolean =
        find(raw(context), username.trim().lowercase()) != null

    /** 帳號 id 一律長這樣，門禁與分級規則可比對 `local:名稱` 或直接寫名稱 */
    private fun idOf(username: String) = "local:$username"

    // ------------------------------------------------------------ 註冊 / 登入

    fun register(
        context: Context,
        usernameRaw: String,
        password: String,
        displayNameRaw: String = ""
    ): Result<Account> = runCatching {
        val username = usernameRaw.trim().lowercase()
        if (!USERNAME_RE.matches(username)) {
            throw IllegalArgumentException("帳號需 3–20 字元，只能用小寫英文、數字、底線與點")
        }
        if (password.length < MIN_PASSWORD) {
            throw IllegalArgumentException("密碼至少 $MIN_PASSWORD 個字元")
        }
        val arr = raw(context)
        if (find(arr, username) != null) {
            throw IllegalArgumentException("這個帳號已經存在")
        }

        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val now = System.currentTimeMillis()
        arr.put(
            JSONObject().apply {
                put("username", username)
                put("displayName", displayNameRaw.trim().ifBlank { username })
                put("salt", b64(salt))
                put("hash", b64(derive(password, salt)))
                put("createdAt", now)
                put("lastLoginAt", now)
            }
        )
        save(context, arr)
        signIn(context, username)
    }

    fun login(context: Context, usernameRaw: String, password: String): Result<Account> =
        runCatching {
            val username = usernameRaw.trim().lowercase()
            val rec = find(raw(context), username)
                ?: throw IllegalArgumentException("找不到這個帳號")
            if (!verify(password, rec.optString("salt"), rec.optString("hash"))) {
                throw IllegalArgumentException("密碼錯誤")
            }
            val arr = raw(context)
            find(arr, username)?.put("lastLoginAt", System.currentTimeMillis())
            save(context, arr)
            signIn(context, username)
        }

    /** 把某個本機帳號設為目前登入者 */
    private fun signIn(context: Context, username: String): Account {
        val rec = find(raw(context), username)
        val name = rec?.optString("displayName")?.ifBlank { username } ?: username
        val account = Account(
            id = idOf(username),
            email = "",
            displayName = name,
            provider = Provider.LOCAL,
            signedInAt = System.currentTimeMillis()
        )
        Prefs.setAccount(context, account)
        return account
    }

    fun changePassword(context: Context, oldPassword: String, newPassword: String): Result<Unit> =
        runCatching {
            val current = Auth.current(context)
                ?: throw IllegalArgumentException("尚未登入")
            if (current.provider != Provider.LOCAL) {
                throw IllegalArgumentException("目前不是 BenStore 帳號")
            }
            val username = current.id.removePrefix("local:")
            val arr = raw(context)
            val rec = find(arr, username) ?: throw IllegalArgumentException("找不到這個帳號")
            if (!verify(oldPassword, rec.optString("salt"), rec.optString("hash"))) {
                throw IllegalArgumentException("目前密碼不正確")
            }
            if (newPassword.length < MIN_PASSWORD) {
                throw IllegalArgumentException("新密碼至少 $MIN_PASSWORD 個字元")
            }
            val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
            rec.put("salt", b64(salt))
            rec.put("hash", b64(derive(newPassword, salt)))
            save(context, arr)
        }

    fun rename(context: Context, displayName: String): Result<Unit> = runCatching {
        val current = Auth.current(context) ?: throw IllegalArgumentException("尚未登入")
        if (current.provider != Provider.LOCAL) throw IllegalArgumentException("目前不是 BenStore 帳號")
        val username = current.id.removePrefix("local:")
        val arr = raw(context)
        find(arr, username)?.put("displayName", displayName.trim().ifBlank { username })
        save(context, arr)
        signIn(context, username)   // 重寫 session，讓顯示名稱立即更新
    }

    /**
     * 刪除帳號。注意：本機帳號沒有救援機制，
     * 忘了密碼只能刪掉重建（帳號內的忽略清單等資料會一起清掉）。
     */
    fun delete(context: Context, username: String) {
        val arr = raw(context)
        val next = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("username") == username.trim().lowercase()) continue
            next.put(o)
        }
        save(context, next)
        val currentId = Prefs.signedInId(context)
        if (currentId == idOf(username.trim().lowercase())) {
            Prefs.clearAccount(context)
        }
    }
}
