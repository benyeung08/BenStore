package com.benstore.app

import android.content.Context
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * 離線授權（License）：用 RSA 簽名做到**無法偽造**的授權。
 *
 * 與存取碼（Access.kt）的差別：
 *   存取碼   = 一串字串，repo.json 存 SHA-256，簡單但拿到就能轉傳
 *   License  = 你用私鑰簽發，App 用內嵌公鑰驗簽
 *              → 無法偽造、可綁定 email、可設到期日
 *
 * 兩者都不需要後端、不需要 OAuth、不需要聯網。
 *
 * 格式：`base64url(payloadJson).base64url(signature)`
 * 簽名算法：SHA256withRSA（PKCS#1 v1.5），與 tools/license_key.py 一致。
 */
object License {

    private const val TAG = "BenStore.License"

    /** 是否已內嵌公鑰（沒內嵌就整個機制停用） */
    fun configured(): Boolean = BuildConfig.LICENSE_PUBKEY.isNotBlank()

    /** License 裡解出來的資訊 */
    data class Claims(
        val email: String,
        val tiers: List<String>,
        val issuedAt: Long,
        val expiresAt: Long,
        val note: String
    ) {
        fun isExpired(now: Long = System.currentTimeMillis() / 1000): Boolean =
            expiresAt > 0 && now > expiresAt

        /** 距離到期還有幾天（已過期回傳負數） */
        fun daysLeft(now: Long = System.currentTimeMillis() / 1000): Long =
            if (expiresAt <= 0) Long.MAX_VALUE else (expiresAt - now) / 86400
    }

    fun current(context: Context): Claims? = Prefs.licenseClaims(context)

    /** 目前這張 license 解鎖的分級（過期就不算） */
    fun grantedTiers(context: Context): Set<String> {
        val c = current(context) ?: return emptySet()
        if (c.isExpired()) return emptySet()
        return c.tiers.map { it.lowercase() }.toSet()
    }

    /**
     * 驗證並啟用一張 license。
     * @return 成功回傳 Claims；失敗回傳 null，並透過 [error] 回傳原因
     */
    fun redeem(context: Context, input: String, error: (String) -> Unit): Claims? {
        if (!configured()) {
            error("尚未內嵌公鑰：請在 build.gradle.kts 設定 LICENSE_PUBKEY")
            return null
        }
        val raw = input.trim()
        val parts = raw.split(".")
        if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            error("格式不對，應該是 payload.signature 兩段")
            return null
        }

        val payload = b64(parts[0])
        val sig = b64(parts[1])
        if (payload == null || sig == null) {
            error("Base64 解碼失敗，請確認整串都複製到了")
            return null
        }

        val key = try {
            publicKey()
        } catch (t: Throwable) {
            Log.w(TAG, "公鑰載入失敗", t)
            error("內嵌公鑰格式有誤：${t.message}")
            return null
        }

        val ok = try {
            Signature.getInstance("SHA256withRSA").run {
                initVerify(key)
                update(payload)
                verify(sig)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "驗簽過程出錯", t)
            error("驗簽失敗：${t.message}")
            return null
        }
        if (!ok) {
            error("簽名驗證失敗 —— 這張授權碼不是你用私鑰簽的，或內容被改過")
            return null
        }

        val claims = try {
            parseClaims(payload)
        } catch (t: Throwable) {
            error("授權碼內容無法解析：${t.message}")
            return null
        }

        if (claims.isExpired()) {
            error("這張授權碼已過期")
            return null
        }
        if (claims.tiers.isEmpty()) {
            error("這張授權碼沒有指定任何分級")
            return null
        }

        Prefs.setLicense(context, raw, claims)
        Log.d(TAG, "license 啟用：${claims.email} tiers=${claims.tiers}")
        return claims
    }

    fun clear(context: Context) = Prefs.clearLicense(context)

    /** 過期檢查：背景檢查時呼叫，過期就清掉 */
    fun purgeIfExpired(context: Context) {
        val c = current(context) ?: return
        if (c.isExpired()) {
            Log.d(TAG, "license 已過期，清除")
            clear(context)
        }
    }

    // ---------------------------------------------------------------- 內部

    private fun publicKey(): PublicKey {
        val der = b64(BuildConfig.LICENSE_PUBKEY)
            ?: throw IllegalStateException("公鑰 Base64 解碼失敗")
        return KeyFactory.getInstance("RSA")
            .generatePublic(X509EncodedKeySpec(der))
    }

    /** base64url 解碼，自動補 padding */
    private fun b64(s: String): ByteArray? = try {
        val t = s.trim()
        val pad = "=".repeat((4 - t.length % 4) % 4)
        Base64.decode(t + pad, Base64.URL_SAFE or Base64.NO_WRAP)
    } catch (t: Throwable) {
        null
    }

    private fun parseClaims(payload: ByteArray): Claims {
        val o = JSONObject(String(payload, Charsets.UTF_8))
        val tiers = mutableListOf<String>()
        val arr: JSONArray = o.optJSONArray("tiers") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val v = arr.optString(i).trim().lowercase()
            if (v.isNotBlank()) tiers.add(v)
        }
        return Claims(
            email = o.optString("email", "").trim(),
            tiers = tiers,
            issuedAt = o.optLong("iat", 0L),
            expiresAt = o.optLong("exp", 0L),
            note = o.optString("note", "")
        )
    }
}
