package com.benstore.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * 未登入時的門禁畫面：可用 Google 或 GitHub 帳號登入。
 * 沒設定任何一種時 MainActivity 不會進到這裡。
 */
@Composable
fun LockScreen(onSignedIn: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var deviceCode by remember { mutableStateOf<DeviceCode?>(null) }

    val googleOk = remember { Auth.googleConfigured() }
    val githubOk = remember { Auth.githubConfigured(context) }
    val hasLocal = remember { LocalAuth.hasAccounts(context) }

    // 三種 OAuth 都沒設定時，本機帳號是唯一出路，直接展開
    var showLocal by remember { mutableStateOf(!googleOk && !githubOk) }
    var isRegister by remember { mutableStateOf(false) }

    // ---------- Google ----------
    fun signInGoogle() {
        busy = true
        error = null
        scope.launch {
            Auth.signInWithGoogle(context)
                .onSuccess { onSignedIn() }
                .onFailure { error = it.message ?: "Google 登入失敗" }
            busy = false
        }
    }

    // ---------- GitHub（Device Flow）----------
    fun startGithub() {
        busy = true
        error = null
        scope.launch {
            try {
                deviceCode = GitHubAuth.requestDeviceCode(
                    AuthSettings.clientId(context),
                    AuthSettings.scope(context)
                )
            } catch (t: Throwable) {
                error = "無法開始 GitHub 登入：${t.message}"
            }
            busy = false
        }
    }

    // 取得裝置碼後自動開始輪詢，等使用者在 GitHub 完成授權
    LaunchedEffect(deviceCode) {
        val dc = deviceCode ?: return@LaunchedEffect
        busy = true
        error = null
        Auth.signInWithGitHub(context) {
            GitHubAuth.pollForToken(AuthSettings.clientId(context), dc)
        }.onSuccess {
            deviceCode = null
            onSignedIn()
        }.onFailure {
            error = it.message ?: "GitHub 登入失敗"
            deviceCode = null
        }
        busy = false
    }

    // ---------- BenStore 本機帳號 ----------
    // 零設定方案：不需要任何 Client ID，使用者現場註冊即可進入。
    fun submitLocal(user: String, pass: String) {
        busy = true
        error = null
        scope.launch {
            val r = if (isRegister) {
                LocalAuth.register(context, user, pass)
            } else {
                LocalAuth.login(context, user, pass)
            }
            r.onSuccess { onSignedIn() }
                .onFailure { error = it.message ?: "登入失敗" }
            busy = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.size(104.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Image(
                    painter = painterResource(id = R.drawable.ic_launcher_foreground),
                    contentDescription = null,
                    modifier = Modifier.size(104.dp)
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        Text("BenStore", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            "選擇一種方式登入",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(28.dp))

        // ---- 等待使用者輸入 GitHub 裝置碼 ----
        val dc = deviceCode
        if (dc != null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("請完成 GitHub 授權", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "1. 開啟 ${dc.verificationUri}\n2. 輸入這組代碼：",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(10.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    ) {
                        Text(
                            dc.userCode,
                            style = MaterialTheme.typography.headlineMedium,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { RepoStore.openUrl(context, dc.verificationUri) }) {
                            Text("開啟 GitHub")
                        }
                        OutlinedButton(onClick = { deviceCode = null }) { Text("取消") }
                    }
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(
                        "完成授權後會自動繼續",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            // ---- Google ----
            if (googleOk) {
                Button(
                    onClick = { signInGoogle() },
                    enabled = !busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(26.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Color(0xFF1F1F1F)
                    )
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = Color(0xFF1F1F1F)
                        )
                    } else {
                        Text("使用 Google 帳號登入")
                    }
                }
                Text(
                    "裝置不支援時會自動改用瀏覽器登入",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            // ---- GitHub ----
            if (githubOk) {
                if (googleOk) Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { startGithub() },
                    enabled = !busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(26.dp)
                ) { Text("使用 GitHub 帳號登入") }
            }

            if (!googleOk && !githubOk) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "尚未設定 Google / GitHub 登入",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "可以直接註冊 BenStore 本機帳號使用（不需任何設定）。\n" +
                                "或由開發者設定後改用 Google / GitHub 登入：\n\n" +
                                "Google：到 Cloud Console 建「網頁應用程式」型別的 OAuth ID，" +
                                "填進 local.properties 的 WEB_CLIENT_ID。\n\n" +
                                "GitHub：到 GitHub 註冊 OAuth App，在設定頁填入 Client ID。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // ---- BenStore 本機帳號（永遠可用，零設定）----
            Spacer(Modifier.height(16.dp))
            if (showLocal) {
                LocalAccountForm(
                    isRegister = isRegister,
                    hasLocal = hasLocal,
                    enabled = !busy,
                    onSubmit = ::submitLocal,
                    onToggleMode = { isRegister = !isRegister },
                    onCancel = { showLocal = false }
                )
            } else {
                TextButton(onClick = { showLocal = true }, enabled = !busy) {
                    Text(
                        if (hasLocal) "使用 BenStore 本機帳號登入"
                        else "註冊 BenStore 本機帳號"
                    )
                }
            }
        }

        error?.let {
            Spacer(Modifier.height(16.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    it,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        val allowed = Auth.allowedEmails()
        Text(
            when {
                allowed.isEmpty() -> "目前未限制帳號（任何帳號皆可登入）"
                else -> "僅限 ${allowed.size} 個已授權帳號使用"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * BenStore 本機帳號的登入 / 註冊表單。
 *
 * 這是「沒設定任何 OAuth」時唯一的入口，所以永遠顯示、永遠可用：
 * 帳號與密碼只存在這支手機上（PBKDF2 雜湊 + Keystore 加密），不連任何伺服器。
 */
@Composable
private fun LocalAccountForm(
    isRegister: Boolean,
    hasLocal: Boolean,
    enabled: Boolean,
    onSubmit: (String, String) -> Unit,
    onToggleMode: () -> Unit,
    onCancel: () -> Unit
) {
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                if (isRegister) "註冊本機帳號" else "本機帳號登入",
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(Modifier.height(10.dp))

            OutlinedTextField(
                value = user,
                onValueChange = { user = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("帳號") },
                placeholder = { Text("小寫英文、數字、底線，3–20 字元") },
                singleLine = true,
                enabled = enabled,
                shape = RoundedCornerShape(12.dp)
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = pass,
                onValueChange = { pass = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("密碼") },
                singleLine = true,
                enabled = enabled,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                shape = RoundedCornerShape(12.dp)
            )
            Spacer(Modifier.height(12.dp))

            Button(
                onClick = { onSubmit(user, pass) },
                enabled = enabled && user.isNotBlank() && pass.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (isRegister) "註冊並進入" else "登入") }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                TextButton(onClick = onToggleMode, enabled = enabled) {
                    Text(if (isRegister) "已有帳號，改登入" else "還沒有帳號，註冊一個")
                }
                if (hasLocal || isRegister) {
                    TextButton(onClick = onCancel, enabled = enabled) { Text("收起") }
                }
            }

            Text(
                "帳號只存在這支手機上，換機不會同步，也沒有忘記密碼救援機制。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 登入成功但不在允許清單裡。
 */
@Composable
fun NotAuthorizedScreen(account: Account, onSignOut: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "此帳號未獲授權",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.error
        )
        Spacer(Modifier.height(12.dp))
        Text(account.title(), style = MaterialTheme.typography.bodyLarge)
        Text(
            "（${account.provider.label}）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "BenStore 只開放給指定的帳號。請換一個帳號，或把這個帳號加進 " +
                "local.properties 的 ALLOWED_EMAILS 後重新編譯。\n" +
                "本機帳號請寫「local:帳號名」或直接寫帳號名。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        OutlinedButton(
            onClick = {
                working = true
                scope.launch {
                    Auth.signOut(context)
                    onSignOut()
                }
            },
            enabled = !working
        ) { Text("登出並更換帳號") }
    }
}
