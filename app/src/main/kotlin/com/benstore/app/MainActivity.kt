package com.benstore.app

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_OPEN_UPDATES = "open_updates"
    }

    private var openUpdates by mutableStateOf(false)
    private var authTick by mutableStateOf(0)
    private var navTick by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UpdateScheduler.schedule(this)
        openUpdates = intent?.getBooleanExtra(EXTRA_OPEN_UPDATES, false) == true

        setContent {
            BenStoreTheme {
                SystemBarAppearance()

                // 放在門禁之前：強制登入模式下使用者第一次開 App 只會看到
                // LockScreen，若把權限請求留在 StoreApp，Android 13+ 要等登入後
                // 才會詢問；而 UpdateScheduler 在 onCreate 就排程了，
                // 登入前的背景檢查通知會全部靜默丟失。
                RequestNotificationPermissionIfNeeded()

                val account = androidx.compose.runtime.remember(authTick) {
                    Auth.current(this@MainActivity)
                }
                when {
                    // 公開模式（PUBLIC_MODE = true）→ 不擋，直接進商店
                    !Auth.gateEnabled(this@MainActivity) ->
                        StoreApp(startTab = if (openUpdates) 1 else 0, navTick = navTick)
                    // 沒登入 → 停在門禁畫面（Google / GitHub / 本機帳號 三選一）
                    account == null -> LockScreen { authTick++ }
                    // 已登入但不在 ALLOWED_EMAILS 名單內
                    !Auth.isAuthorized(this@MainActivity) ->
                        NotAuthorizedScreen(account) { authTick++ }
                    else -> StoreApp(startTab = if (openUpdates) 1 else 0, navTick = navTick)
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openUpdates = intent.getBooleanExtra(EXTRA_OPEN_UPDATES, false)
        // 即使 startTab 值不變（都是「更新」），也讓 StoreApp 重新套用分頁
        navTick++
    }
}

private val LightColors = lightColorScheme(
    primary = Color(0xFF1565C0),
    onPrimary = Color.White,
    secondary = Color(0xFF00897B),
    surface = Color(0xFFFAFAFA)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF90CAF9),
    onPrimary = Color(0xFF0D47A1),
    secondary = Color(0xFF80CBC4)
)

/**
 * themes.xml 把 `windowLightStatusBar` 寫死為 true（深色圖示），
 * 但 BenStoreTheme 會跟著系統切深色配色、狀態欄又是透明背景。
 * 深色模式下就會變成「深色圖示壓在深色內容上」，幾乎看不見。
 *
 * 這裡在每次重組後依實際深淺設定系統欄圖示顏色。
 * 刻意不改變 decor 與系統欄的貼合設定──若讓內容延伸到狀態欄後面，
 * 而 Compose 目前沒有加 statusBarsPadding()，TopAppBar 會被蓋住。
 */
@Composable
private fun SystemBarAppearance() {
    val view = LocalView.current
    val dark = isSystemInDarkTheme()
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        WindowInsetsControllerCompat(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
}

@Composable
fun BenStoreTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
