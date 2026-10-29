package com.benstore.app

import android.content.Context
import java.security.MessageDigest
import java.util.Locale

/**
 * 存取碼（Access Code）分級 —— 不需要 OAuth、不需要後端、不需要 Client ID。
 *
 * 運作方式：
 *  1. repo.json 頂層 `tiers` 定義若干分級，每個分級放「存取碼的 SHA-256」
 *  2. 每個應用可用 `requiredTiers` 指定「屬於哪個分級才看得到」
 *  3. 使用者在設定頁輸入存取碼 → App 算出 SHA-256 → 比對命中哪個分級
 *
 * 只存雜湊不存明文，所以 repo.json 就算公開也無法反推存取碼。
 * 這不是密碼學強認證，但對「限定誰能下載私有應用」這個目的完全夠用，
 * 而且完全不需要架任何東西。
 */
object Access {

    /** 本機已解鎖的分級（存取碼） */
    fun grantedTiers(c: Context): Set<String> =
        Prefs.accessTiers(c).map { it.lowercase(Locale.ROOT) }.toSet()

    /**
     * 全部已解鎖的分級 = 存取碼 + 離線授權（License）。
     * UI 過濾一律用這個，兩種機制任一命中即可看見。
     */
    fun allTiers(c: Context): Set<String> =
        grantedTiers(c) + License.grantedTiers(c)

    /** 是否已解鎖任一分級 */
    fun hasAny(c: Context): Boolean = grantedTiers(c).isNotEmpty()

    /** 使用者輸入的識別名稱（方便顯示「你以 XX 身分使用」） */
    fun grantedLabel(c: Context): String = Prefs.accessLabel(c)

    /** 資料源是否用到了分級（沒用到就不用顯示輸入框） */
    fun tiersDefined(repo: AppRepo?): Boolean = repo?.tiers?.isNotEmpty() == true

    fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(s.trim().toByteArray())
            .joinToString("") { "%02x".format(it) }

    /**
     * 驗證存取碼。
     * @return 命中就回傳該分級定義（含 label），否則 null
     */
    fun redeem(repo: AppRepo?, code: String): Tier? {
        val r = repo ?: return null
        val h = sha256(code)
        if (h.isBlank()) return null
        return r.tierForHash(h)
    }

    /** 兌換成功：寫入本機 */
    fun grant(c: Context, tier: Tier, code: String) {
        Prefs.setAccessTiers(c, grantedTiers(c) + tier.key)
        // 用碼的末 4 碼當顯示名，避免整串碼出現在 UI 上被旁人看到
        val tail = code.trim().takeLast(4)
        val label = tier.label.ifBlank { tier.key }
        Prefs.setAccessLabel(c, "$label · ····$tail")
    }

    /** 清除全部已解鎖分級 */
    fun revokeAll(c: Context) {
        Prefs.setAccessTiers(c, emptySet())
        Prefs.setAccessLabel(c, "")
    }
}
