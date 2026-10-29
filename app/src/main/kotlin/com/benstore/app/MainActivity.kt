package com.benstore.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_OPEN_UPDATES = "open_updates"
    }

    private var openUpdates by mutableStateOf(false)
    private var authTick by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UpdateScheduler.schedule(this)
        openUpdates = intent?.getBooleanExtra(EXTRA_OPEN_UPDATES, false) == true

        setContent {
            BenStoreTheme {
                val account = androidx.compose.runtime.remember(authTick) {
                    Auth.current(this@MainActivity)
                }
                when {
                    // 公開模式、或兩種登入都沒設定 → 不擋，直接進商店
                    !Auth.gateEnabled(this@MainActivity) ->
                        StoreApp(startTab = if (openUpdates) 1 else 0)
                    account == null -> LockScreen { authTick++ }
                    !Auth.isAuthorized(this@MainActivity) ->
                        NotAuthorizedScreen(account) { authTick++ }
                    else -> StoreApp(startTab = if (openUpdates) 1 else 0)
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openUpdates = intent.getBooleanExtra(EXTRA_OPEN_UPDATES, false)
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
