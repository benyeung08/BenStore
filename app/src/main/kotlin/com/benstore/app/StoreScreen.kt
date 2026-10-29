package com.benstore.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed class StoreState {
    object Loading : StoreState()
    data class Ready(val repo: AppRepo) : StoreState()
    data class Error(val message: String) : StoreState()
}

private const val TAB_APPS = 0
private const val TAB_UPDATES = 1
private const val TAB_SETTINGS = 2

@Composable
private fun RequestNotificationPermissionIfNeeded() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@Composable
fun StoreApp(startTab: Int = TAB_APPS) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    RequestNotificationPermissionIfNeeded()
    LaunchedEffect(Unit) { Notifier.createChannels(context) }

    var repoUrl by remember { mutableStateOf(Prefs.repoUrl(context)) }
    var state by remember { mutableStateOf<StoreState>(StoreState.Loading) }
    var tab by remember { mutableIntStateOf(startTab) }
    var detailPackage by remember { mutableStateOf<String?>(null) }
    // 存取碼兌換後 +1，讓列表重新過濾
    var tiersTick by remember { mutableIntStateOf(0) }

    fun reload() {
        scope.launch {
            state = StoreState.Loading
            state = try {
                StoreState.Ready(RepoStore.load(context, repoUrl, AuthSettings.token(context)))
            } catch (t: Throwable) {
                StoreState.Error(t.message ?: "載入失敗")
            }
        }
    }

    // 從通知點進來時，直接切到「更新」分頁
    LaunchedEffect(startTab) { tab = startTab }

    LaunchedEffect(repoUrl) {
        Prefs.setRepoUrl(context, repoUrl)
        reload()
    }

    when (val s = state) {
        StoreState.Loading -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text("正在載入資料源…")
                }
            }
        }

        is StoreState.Error -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(24.dp)
                ) {
                    Text("載入失敗：${s.message}")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "請到「設定」確認資料源網址是否正確、檔案是否公開可讀。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { reload() }) { Text("重試") }
                        OutlinedButton(onClick = { tab = TAB_SETTINGS }) { Text("去設定") }
                    }
                }
            }
        }

        is StoreState.Ready -> {
            val repo = s.repo
            val account = Auth.current(context)
            val selected = detailPackage?.let { pkg ->
                repo.find(pkg)?.takeIf {
                    isVisibleTo(it, account, Access.allTiers(context))
                }
            }
            if (selected != null) {
                BackHandler { detailPackage = null }
                AppDetailScreen(app = selected, repo = repo, onBack = { detailPackage = null })
            } else {
                MainScaffold(
                    repo = repo,
                    tab = tab,
                    onTabChange = { tab = it },
                    onRefresh = { reload() },
                    onOpenDetail = { detailPackage = it },
                    repoUrl = repoUrl,
                    onRepoChange = { repoUrl = it },
                    tiersTick = tiersTick,
                    onTiersChanged = { tiersTick++ }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScaffold(
    repo: AppRepo,
    tab: Int,
    onTabChange: (Int) -> Unit,
    onRefresh: () -> Unit,
    onOpenDetail: (String) -> Unit,
    repoUrl: String,
    onRepoChange: (String) -> Unit,
    tiersTick: Int = 0,
    onTiersChanged: () -> Unit = {}
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (tab) {
                            TAB_UPDATES -> "更新"
                            TAB_SETTINGS -> "設定"
                            else -> {
                                val n = if (Auth.tieringEnabled())
                                    repo.visibleTo(
                                        Auth.current(context),
                                        Access.allTiers(context)
                                    ).size
                                else repo.apps.size
                                "${repo.name} · $n 個應用"
                            }
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                actions = {
                    if (tab == TAB_APPS) {
                        IconButton(onClick = onRefresh) {
                            Icon(Icons.Filled.Refresh, contentDescription = "重新載入")
                        }
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == TAB_APPS,
                    onClick = { onTabChange(TAB_APPS) },
                    icon = { Icon(Icons.Filled.Apps, contentDescription = null) },
                    label = { Text("應用") }
                )
                NavigationBarItem(
                    selected = tab == TAB_UPDATES,
                    onClick = { onTabChange(TAB_UPDATES) },
                    icon = { Icon(Icons.Filled.SystemUpdate, contentDescription = null) },
                    label = { Text("更新") }
                )
                NavigationBarItem(
                    selected = tab == TAB_SETTINGS,
                    onClick = { onTabChange(TAB_SETTINGS) },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { Text("設定") }
                )
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                TAB_APPS -> AppListScreen(
                    repo = repo,
                    onOpen = { onOpenDetail(it.packageName) },
                    tiersTick = tiersTick
                )

                TAB_UPDATES -> UpdatesScreen(
                    repo = repo,
                    onOpen = { onOpenDetail(it.packageName) }
                )

                else -> SettingsScreen(
                    repoUrl = repoUrl,
                    onRepoChange = onRepoChange,
                    onReload = onRefresh,
                    repo = repo,
                    onTiersChanged = onTiersChanged
                )
            }
        }
    }
}

// ---------------------------------------------------------------- 應用列表

@Composable
private fun AppListScreen(
    repo: AppRepo,
    onOpen: (RepoApp) -> Unit,
    tiersTick: Int = 0
) {
    val context = LocalContext.current
    val account = remember { Auth.current(context) }

    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<String?>(null) }

    // 權限分級：公開模式顯示全部，否則只列出這個使用者看得見的
    // （存取碼分級 + 帳號分級兩套機制）
    val visible = remember(repo, account, tiersTick) {
        if (Auth.tieringEnabled()) {
            repo.visibleTo(account, Access.allTiers(context))
        } else repo.apps
    }

    val categories = remember(visible) {
        visible.asSequence()
            .flatMap { it.categories.asSequence() }
            .groupingBy { it }
            .eachCount()
            .toList()
            .sortedByDescending { it.second }
            .map { it.first }
    }

    val filtered = remember(visible, query, category) {
        val q = query.trim().lowercase()
        visible.filter { app ->
            (category == null || app.categories.contains(category)) &&
                (q.isBlank() ||
                    app.name.lowercase().contains(q) ||
                    app.summary.lowercase().contains(q) ||
                    app.packageName.lowercase().contains(q))
        }
    }

    Column {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            placeholder = { Text("搜尋應用名稱或包名") },
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )

        if (categories.isNotEmpty()) {
            Row(
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                FilterChip(
                    selected = category == null,
                    onClick = { category = null },
                    label = { Text("全部") },
                    modifier = Modifier.padding(end = 8.dp)
                )
                categories.forEach { c ->
                    FilterChip(
                        selected = category == c,
                        onClick = { category = if (category == c) null else c },
                        label = { Text(c) },
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
            }
        }

        if (visible.size < repo.apps.size) {
            Text(
                "依你的帳號顯示 ${visible.size}/${repo.apps.size} 個應用",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }

        if (filtered.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (repo.apps.isEmpty()) "資料源裡沒有應用" else "沒有符合的應用"
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filtered, key = { it.packageName }) { app ->
                    AppRow(app = app, onClick = { onOpen(app) })
                }
            }
        }
    }
}

/** 圖示改成從網址載入，記憶體 + 磁碟雙層快取 */
@Composable
fun AppIcon(app: RepoApp, size: Int) {
    val context = LocalContext.current
    var bitmap by remember(app.iconUrl) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(app.iconUrl) {
        if (app.iconUrl.isBlank()) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val b = RepoStore.loadIcon(context, app.iconUrl)
            withContext(kotlinx.coroutines.Dispatchers.Main) { bitmap = b }
        }
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.size(size.dp)
        )
    } else {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.size(size.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = app.name.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
    }
}

@Composable
private fun AppRow(app: RepoApp, onClick: () -> Unit) {
    val latest = remember(app) { app.versions.firstOrNull() }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(app, 48)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = app.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (app.summary.isNotBlank()) {
                    Text(
                        text = app.summary,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    latest?.versionName ?: "",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

// ---------------------------------------------------------------- 更新

@Composable
private fun UpdatesScreen(repo: AppRepo, onOpen: (RepoApp) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var scanning by remember { mutableStateOf(true) }
    var updates by remember { mutableStateOf<List<AppUpdate>>(emptyList()) }
    var tracked by remember { mutableIntStateOf(0) }
    var refreshKey by remember { mutableIntStateOf(0) }

    val selected: SnapshotStateList<String> = remember { mutableStateListOf() }
    var working by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf(0f) }

    fun rescan() {
        scope.launch {
            scanning = true
            val result = withContext(Dispatchers.Default) {
                UpdateScanner.scan(context, repo) to UpdateScanner.trackedCount(context, repo)
            }
            updates = result.first
            tracked = result.second
            selected.clear()
            selected.addAll(updates.map { it.app.packageName })
            scanning = false
        }
    }

    LaunchedEffect(repo, refreshKey) { rescan() }

    fun ensureInstallPermission(): Boolean {
        if (!RepoStore.canInstall(context)) {
            RepoStore.openInstallPermissionSettings(context)
            return false
        }
        return true
    }

    /** 逐一下載並送出安裝請求 */
    fun installList(list: List<AppUpdate>) {
        if (list.isEmpty() || !ensureInstallPermission()) return
        scope.launch {
            working = true
            progress = 0f
            var ok = 0
            list.forEachIndexed { i, u ->
                status = "(${i + 1}/${list.size}) 正在下載 ${u.app.name}"
                progress = i.toFloat() / list.size.toFloat()
                try {
                    val file = RepoStore.downloadApk(
                        context, u.app.packageName, u.version, AuthSettings.token(context)
                    ) { _, _ -> }
                    status = "(${i + 1}/${list.size}) 正在安裝 ${u.app.name}"
                    runInstall(context, file, u.app.packageName)
                    ok++
                } catch (t: Throwable) {
                    status = "${u.app.name} 失敗：${t.message}"
                }
                delay(400)
            }
            status = "已送出 $ok/${list.size} 個安裝請求，請在系統彈窗確認"
            progress = 1f
            working = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        scanning -> "正在比對已安裝應用…"
                        updates.isEmpty() -> "所有應用都是最新版本"
                        else -> "${updates.size} 個更新可用"
                    },
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    "追蹤 $tracked 個已安裝應用",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { refreshKey++ }, enabled = !scanning && !working) {
                Icon(Icons.Filled.Refresh, contentDescription = "重新檢查")
            }
        }

        if (scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (working) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth()
            )
        }

        status?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )
        }

        if (!scanning && updates.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        installList(updates.filter { selected.contains(it.app.packageName) })
                    },
                    enabled = !working && selected.isNotEmpty(),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.Download, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("更新所選 (${selected.size})")
                }
                OutlinedButton(
                    onClick = {
                        if (selected.size == updates.size) selected.clear()
                        else {
                            selected.clear()
                            selected.addAll(updates.map { it.app.packageName })
                        }
                    },
                    enabled = !working
                ) { Text(if (selected.size == updates.size) "全不選" else "全選") }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(updates, key = { it.app.packageName }) { u ->
                UpdateRow(
                    update = u,
                    checked = selected.contains(u.app.packageName),
                    onCheckedChange = { on ->
                        if (on) selected.add(u.app.packageName)
                        else selected.remove(u.app.packageName)
                    },
                    onOpen = { onOpen(u.app) },
                    onUpdate = { installList(listOf(u)) },
                    onIgnore = {
                        Prefs.ignoreVersion(context, u.app.packageName, u.version.versionCode)
                        refreshKey++
                    },
                    enabled = !working
                )
            }
        }
    }
}

@Composable
private fun UpdateRow(
    update: AppUpdate,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onOpen: () -> Unit,
    onUpdate: () -> Unit,
    onIgnore: () -> Unit,
    enabled: Boolean
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
            Box(Modifier.clickable { onOpen() }) { AppIcon(update.app, 44) }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    update.app.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${update.installedVersionName} → ${update.version.versionName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    RepoStore.formatSize(update.version.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Button(onClick = onUpdate, enabled = enabled) { Text("更新") }
                TextButton(onClick = onIgnore, enabled = enabled) { Text("忽略") }
            }
        }
    }
}

// ---------------------------------------------------------------- 設定

@Composable
private fun SettingsScreen(
    repoUrl: String,
    onRepoChange: (String) -> Unit,
    onReload: () -> Unit,
    repo: AppRepo? = null,
    onTiersChanged: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var urlDraft by remember(repoUrl) { mutableStateOf(repoUrl) }
    var autoCheck by remember { mutableStateOf(Prefs.autoCheck(context)) }
    var interval by remember { mutableIntStateOf(Prefs.intervalHours(context)) }
    var wifiOnly by remember { mutableStateOf(Prefs.wifiOnly(context)) }
    var autoInstall by remember { mutableStateOf(Prefs.autoInstall(context)) }
    var lastCheck by remember { mutableStateOf(Prefs.lastCheck(context)) }
    var ignoredCount by remember { mutableStateOf(Prefs.ignored(context).size) }
    var running by remember { mutableStateOf(false) }

    val canInstall = remember { RepoStore.canInstall(context) }
    val canNotify = remember { Notifier.canNotify(context) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("資料來源", style = MaterialTheme.typography.titleMedium)
        Text(
            "指向你自己的 repo.json（HTTPS 公開可讀即可）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = urlDraft,
            onValueChange = { urlDraft = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("https://.../repo.json") },
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { val v = urlDraft.trim(); if (v.isNotBlank()) onRepoChange(v) },
                enabled = urlDraft.trim().isNotBlank() && urlDraft.trim() != repoUrl
            ) { Text("儲存並載入") }
            OutlinedButton(onClick = { urlDraft = repoUrl }) { Text("還原") }
        }
        Text(
            "目前：$repoUrl",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(20.dp))
        AccessCodeSection(repo = repo, onTiersChanged = onTiersChanged)
        Spacer(Modifier.height(20.dp))
        LicenseSection(onTiersChanged = onTiersChanged)
        Spacer(Modifier.height(20.dp))
        AccountSection()
        Spacer(Modifier.height(20.dp))
        GitHubRepoSection(onRepoReload = onReload)
        Spacer(Modifier.height(20.dp))

        Text("背景更新檢查", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))

        SettingSwitch(
            "自動檢查更新",
            "依照下面的間隔，在背景比對版本並發送通知",
            autoCheck
        ) {
            autoCheck = it
            Prefs.setAutoCheck(context, it)
            UpdateScheduler.schedule(context)
        }

        Text("檢查間隔", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(6, 12, 24).forEach { h ->
                FilterChip(
                    selected = interval == h,
                    onClick = {
                        interval = h
                        Prefs.setIntervalHours(context, h)
                        UpdateScheduler.schedule(context)
                    },
                    label = { Text("${h} 小時") }
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        SettingSwitch("只在 Wi-Fi 下檢查", "避免行動數據下載", wifiOnly) {
            wifiOnly = it
            Prefs.setWifiOnly(context, it)
            UpdateScheduler.schedule(context)
        }

        SettingSwitch(
            "自動下載並安裝",
            "背景檢查到更新時直接下載並送出安裝請求；每個應用仍會彈一次系統確認",
            autoInstall
        ) {
            autoInstall = it
            Prefs.setAutoInstall(context, it)
        }

        Spacer(Modifier.height(12.dp))

        Button(
            onClick = {
                running = true
                UpdateScheduler.checkNow(context)
                scope.launch {
                    delay(3000)
                    lastCheck = Prefs.lastCheck(context)
                    running = false
                }
            },
            enabled = !running,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (running) "正在背景檢查…" else "立即檢查更新") }

        Text(
            "上次檢查：${formatTime(lastCheck)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "檢查會重新抓取 repo.json，有更新時會發通知；也可到「更新」分頁手動重新檢查。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(20.dp))
        Text("權限", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))

        SettingRow(
            "允許安裝應用",
            if (canInstall) "已授權" else "未授權，更新時會被系統擋下"
        ) { RepoStore.openInstallPermissionSettings(context) }

        SettingRow(
            "更新通知",
            if (canNotify) "已授權" else "未授權，不會收到更新提醒"
        ) { Notifier.createChannels(context) }

        Spacer(Modifier.height(20.dp))
        Text("其他", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))

        SettingRow(
            "已忽略的版本",
            if (ignoredCount == 0) "無" else "$ignoredCount 個版本被標記忽略"
        ) {
            Prefs.setIgnored(context, emptySet())
            ignoredCount = 0
        }

        SettingRow("重新載入資料源", "重新抓取 repo.json") { onReload() }

        Spacer(Modifier.height(24.dp))
    }
}

// ---------------------------------------------------------------- GitHub 資料源設定

/**
 * GitHub OAuth App 設定 + token 狀態。
 *
 * 注意：登入動作統一在 AccountSection（選 Google 或 GitHub），
 * 這裡只管「讀私有 repo 用的憑證設定」，不再重複一套登入 UI。
 */
@Composable
private fun GitHubRepoSection(onRepoReload: () -> Unit) {
    val context = LocalContext.current

    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    var clientIdDraft by remember { mutableStateOf(AuthSettings.clientId(context)) }
    var editingId by remember { mutableStateOf(false) }
    var secretDraft by remember { mutableStateOf(AuthSettings.clientSecret(context)) }
    var editingSecret by remember { mutableStateOf(false) }
    var scopeDraft by remember { mutableStateOf(AuthSettings.scope(context)) }
    var editingScope by remember { mutableStateOf(false) }

    val hasToken = remember { AuthSettings.isLoggedIn(context) }
    val user = remember { AuthSettings.currentUser(context) }

    Text("GitHub 資料源設定", style = MaterialTheme.typography.titleMedium)
    Text(
        "設定 OAuth App 憑證，才能讀取私有倉庫的 repo.json",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(8.dp))

    // ---- Client ID ----
    if (editingId) {
        OutlinedTextField(
            value = clientIdDraft,
            onValueChange = { clientIdDraft = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("OAuth App Client ID") },
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                AuthSettings.setClientId(context, clientIdDraft)
                editingId = false
            }) { Text("儲存") }
            OutlinedButton(onClick = {
                clientIdDraft = AuthSettings.clientId(context)
                editingId = false
            }) { Text("取消") }
        }
    } else {
        SettingRow(
            "Client ID",
            if (AuthSettings.isConfigured(context))
                AuthSettings.clientId(context)
            else "尚未設定，點這裡填入（到 GitHub 註冊 OAuth App 並啟用 Device Flow）"
        ) { editingId = true }
    }

    // ---- Scope ----
    if (editingScope) {
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = scopeDraft,
            onValueChange = { scopeDraft = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Scope") },
            supportingText = { Text("repo = 讀私有倉庫；user:email = 取得身份用信箱") },
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                AuthSettings.setScope(context, scopeDraft)
                editingScope = false
            }) { Text("儲存") }
            OutlinedButton(onClick = {
                scopeDraft = AuthSettings.scope(context)
                editingScope = false
            }) { Text("取消") }
        }
    } else {
        SettingRow("Scope", AuthSettings.scope(context)) { editingScope = true }
    }

    // ---- Secret（選填）----
    if (editingSecret) {
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = secretDraft,
            onValueChange = { secretDraft = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("OAuth App Client Secret（選填）") },
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                AuthSettings.setClientSecret(context, secretDraft)
                editingSecret = false
            }) { Text("儲存") }
            OutlinedButton(onClick = {
                secretDraft = AuthSettings.clientSecret(context)
                editingSecret = false
            }) { Text("取消") }
        }
    } else {
        SettingRow(
            "Client Secret（選填）",
            if (AuthSettings.clientSecret(context).isBlank())
                "未設定 — Device Flow 不需要"
            else "已設定（會被包進 APK，可被反編譯取出）"
        ) { editingSecret = true }
    }

    Spacer(Modifier.height(12.dp))

    // ---- token 狀態 ----
    if (hasToken && user != null) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    "已取得存取權：${user.name.ifBlank { user.login }}",
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    "token 加密存放於本機（AES-256-GCM + Keystore）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        busy = true
                        AuthSettings.clearSession(context)
                        message = "已清除本機 token"
                        busy = false
                        onRepoReload()
                    },
                    enabled = !busy
                ) { Text("清除 token") }
            }
        }
    }

    message?.let {
        Spacer(Modifier.height(8.dp))
        Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ---------------------------------------------------------------- 帳號（Google / GitHub）

/**
 * 存取碼分級：不需要 OAuth、不需要後端、不需要 Client ID。
 * 使用者輸入一組碼 → 算出 SHA-256 → 命中 repo.json 裡的哪個分級。
 */
@Composable
private fun AccessCodeSection(repo: AppRepo?, onTiersChanged: () -> Unit) {
    val context = LocalContext.current

    var code by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var ok by remember { mutableStateOf(false) }
    var granted by remember { mutableStateOf(Access.allTiers(context)) }
    var label by remember { mutableStateOf(Access.grantedLabel(context)) }

    fun refresh() {
        granted = Access.allTiers(context)
        label = Access.grantedLabel(context)
        onTiersChanged()
    }

    Text("存取碼", style = MaterialTheme.typography.titleMedium)
    Text(
        "輸入存取碼解鎖私有應用。不需要 Google 帳號、不需要 OAuth、不需要後端。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(8.dp))

    val defined = repo?.tierList().orEmpty()
    if (defined.isEmpty()) {
        Text(
            "這份資料源沒有定義任何分級（repo.json 裡沒有 tiers），" +
                "所以目前不需要存取碼。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            defined.forEach { t ->
                FilterChip(
                    selected = granted.contains(t.key),
                    onClick = { },
                    enabled = false,
                    label = { Text(t.label) }
                )
            }
        }
    }

    if (granted.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text(
            "已解鎖：${granted.joinToString("、")}" + if (label.isNotBlank()) "（$label）" else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary
        )
    }

    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = code,
        onValueChange = { code = it; message = null },
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("輸入存取碼") },
        singleLine = true,
        shape = RoundedCornerShape(12.dp)
    )
    Spacer(Modifier.height(8.dp))

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = {
                val tier = Access.redeem(repo, code)
                if (tier == null) {
                    ok = false
                    message = "存取碼無效，或這份資料源沒有對應的分級"
                } else {
                    Access.grant(context, tier, code)
                    ok = true
                    message = "已解鎖「${tier.label}」"
                    code = ""
                    refresh()
                }
            },
            enabled = code.trim().isNotBlank()
        ) { Text("兌換") }

        if (granted.isNotEmpty()) {
            OutlinedButton(onClick = {
                Access.revokeAll(context)
                message = "已清除全部存取碼"
                ok = false
                refresh()
            }) { Text("清除") }
        }
    }

    message?.let {
        Spacer(Modifier.height(6.dp))
        Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            color = if (ok) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.error
        )
    }
}

/**
 * 離線授權（License）：RSA 簽名，無法偽造、可綁 email、可設到期日。
 * 與存取碼同樣不需要後端／OAuth，但強度更高。
 */
@Composable
private fun LicenseSection(onTiersChanged: () -> Unit) {
    val context = LocalContext.current

    var text by remember { mutableStateOf(Prefs.licenseRaw(context) ?: "") }
    var message by remember { mutableStateOf<String?>(null) }
    var ok by remember { mutableStateOf(false) }
    var claims by remember { mutableStateOf(License.current(context)) }

    if (!License.configured()) {
        // 沒內嵌公鑰就整塊不顯示，避免誤導
        return
    }

    fun refresh() {
        claims = License.current(context)
        onTiersChanged()
    }

    Text("授權碼", style = MaterialTheme.typography.titleMedium)
    Text(
        "由私鑰簽發，無法偽造，可綁定對象與到期日。不需要後端、不需要聯網。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(8.dp))

    val c = claims
    if (c != null && !c.isExpired()) {
        val days = c.daysLeft()
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = androidx.compose.material3.CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    if (c.email.isNotBlank()) "已授權：${c.email}" else "已授權",
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    "分級：${c.tiers.joinToString("、")}",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    when {
                        days == Long.MAX_VALUE -> "有效期限：永久"
                        days > 0 -> "還有 $days 天到期"
                        else -> "已過期"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (days > 0) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    OutlinedTextField(
        value = text,
        onValueChange = { text = it; message = null },
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("貼上授權碼") },
        singleLine = true,
        shape = RoundedCornerShape(12.dp)
    )
    Spacer(Modifier.height(8.dp))

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = {
                val r = License.redeem(context, text) { err ->
                    ok = false
                    message = err
                }
                if (r != null) {
                    ok = true
                    message = "授權成功" +
                        (if (r.email.isNotBlank()) "：${r.email}" else "") +
                        "（${r.tiers.joinToString("、")}）"
                    text = ""
                    refresh()
                }
            },
            enabled = text.trim().isNotBlank()
        ) { Text("啟用") }

        if (claims != null) {
            OutlinedButton(onClick = {
                License.clear(context)
                message = "已移除授權碼"
                ok = false
                text = ""
                refresh()
            }) { Text("移除") }
        }
    }

    message?.let {
        Spacer(Modifier.height(6.dp))
        Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            color = if (ok) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.error
        )
    }
}

@Composable
private fun AccountSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var account by remember { mutableStateOf(Auth.current(context)) }
    var working by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<DeviceCode?>(null) }

    val googleOk = remember { Auth.googleConfigured() }
    val githubOk = remember { Auth.githubConfigured(context) }

    Text("帳號", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))

    // 公開模式：商店對所有人開放，登入只是選配
    if (Auth.isPublic()) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("公開模式 · 不需要登入", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "任何人都能瀏覽並安裝全部應用，不做門禁也不做分級。" +
                        "（要改回限定帳號，把 build.gradle.kts 的 PUBLIC_MODE 設成 false 重新編譯）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (googleOk || githubOk) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "仍可登入以取得額外能力（例如用 GitHub token 讀私有資料源）：",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        // 公開模式下不 return，讓下面照樣顯示登入入口
        if (!googleOk && !githubOk) {
            Spacer(Modifier.height(8.dp))
            Text(
                "想加入帳號登入？到 Google Cloud Console 建立「網頁應用程式」型別的 OAuth 用戶端 ID，" +
                    "填進 local.properties 的 WEB_CLIENT_ID 後重新編譯。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else if (!googleOk && !githubOk) {
        // 強制登入模式下仍然會擋：此時靠 BenStore 本機帳號進入
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("尚未設定 Google / GitHub", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "目前以 BenStore 本機帳號做門禁（零設定，帳號只存在這支手機）。\n" +
                        "想改用 Google / GitHub 登入，請設定 WEB_CLIENT_ID 或 GitHub Client ID 後重新編譯。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    val allowed = Auth.allowedEmails()
    val a = account

    // 已登入：顯示身份 + 登出
    if (a != null) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text(a.title(), style = MaterialTheme.typography.bodyLarge)
                Text(
                    "${a.provider.label} · ${a.id}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!Auth.isPublic()) {
                    Spacer(Modifier.height(8.dp))
                    val authorized = Auth.isAuthorized(context)
                    Text(
                        if (authorized) "✓ 已授權" else "✗ 不在允許清單中",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (authorized) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        working = true
                        scope.launch {
                            Auth.signOut(context)
                            account = null
                            message = "已登出"
                            working = false
                        }
                    },
                    enabled = !working
                ) { Text("登出") }
            }
        }
    } else if (pending != null) {
        // GitHub 裝置碼等待中
        val dc = pending!!
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("請完成 GitHub 授權", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    "開啟 ${dc.verificationUri} 並輸入：",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(10.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer
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
                    OutlinedButton(onClick = { pending = null }) { Text("取消") }
                }
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    } else {
        // 未登入：列出可用的登入方式
        if (googleOk) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !working) {
                        working = true
                        message = null
                        scope.launch {
                            Auth.signInWithGoogle(context)
                                .onSuccess { account = it }
                                .onFailure { message = it.message }
                            working = false
                        }
                    }
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("使用 Google 帳號登入", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (allowed.isEmpty()) "登入後可依帳號分級顯示應用；裝置不支援時會改用瀏覽器登入"
                        else "僅限 ${allowed.size} 個已授權帳號；不支援時改用瀏覽器登入",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        if (githubOk) {
            Spacer(Modifier.height(8.dp))
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !working) {
                        working = true
                        message = null
                        scope.launch {
                            try {
                                pending = GitHubAuth.requestDeviceCode(
                                    AuthSettings.clientId(context),
                                    AuthSettings.scope(context)
                                )
                            } catch (t: Throwable) {
                                message = "無法開始 GitHub 登入：${t.message}"
                            }
                            working = false
                        }
                    }
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("使用 GitHub 帳號登入", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Device Flow：取得一組短碼，到 GitHub 輸入即完成",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // 完全不用建 OAuth App：使用者自己產生 PAT 貼進來
        Spacer(Modifier.height(8.dp))
        var pat by remember { mutableStateOf("") }
        Text(
            "或直接用 GitHub 權杖（PAT）",
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            "自己到 GitHub → Settings → Developer settings → Personal access tokens 產生，" +
                "不需要為 BenStore 申請任何 OAuth App。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = pat,
            onValueChange = { pat = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("ghp_...") },
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )
        Spacer(Modifier.height(6.dp))
        Button(
            onClick = {
                working = true
                message = null
                scope.launch {
                    Auth.signInWithToken(context, pat)
                        .onSuccess { account = it; pat = "" }
                        .onFailure { message = it.message }
                    working = false
                }
            },
            enabled = pat.trim().isNotBlank() && !working
        ) { Text("用權杖登入") }
    }

    // 裝置碼取得後自動開始輪詢
    LaunchedEffect(pending) {
        val dc = pending ?: return@LaunchedEffect
        working = true
        message = null
        val r = Auth.signInWithGitHub(context) {
            GitHubAuth.pollForToken(AuthSettings.clientId(context), dc)
        }
        r.onSuccess { account = it; pending = null }
            .onFailure { message = it.message }
        pending = null
        working = false
    }

    message?.let {
        Spacer(Modifier.height(8.dp))
        Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }

    Spacer(Modifier.height(4.dp))
    Text(
        "門禁與分級都是單機判定：身份由 Google／GitHub 提供，但 BenStore 沒有驗證 token 簽章。" +
            "要防真正的攻擊者，得在後端驗證。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun SettingSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingRow(title: String, subtitle: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { onClick() }
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun formatTime(ms: Long): String {
    if (ms <= 0L) return "從未"
    return SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(Date(ms))
}

// ---------------------------------------------------------------- 詳情

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppDetailScreen(app: RepoApp, repo: AppRepo, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latest = remember(app) { repo.latest(app) }

    var working by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var status by remember { mutableStateOf<String?>(null) }

    fun startInstall() {
        val v = latest ?: return
        if (!RepoStore.canInstall(context)) {
            RepoStore.openInstallPermissionSettings(context)
            return
        }
        scope.launch {
            working = true
            status = "下載中…"
            progress = 0f
            try {
                val file = RepoStore.downloadApk(
                    context, app.packageName, v, AuthSettings.token(context)
                ) { done, total ->
                    if (total > 0) progress = done.toFloat() / total.toFloat()
                }
                status = "下載完成，正在安裝…"
                runInstall(context, file, app.packageName)
                status = "已送出安裝請求，請在系統彈窗確認"
            } catch (t: Throwable) {
                status = "失敗：${t.message}"
            } finally {
                working = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(app.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(app, 72)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(app.name, style = MaterialTheme.typography.titleLarge)
                    Text(
                        app.packageName,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "版本 ${latest?.versionName ?: "未知"} · ${RepoStore.formatSize(latest?.size ?: 0L)}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            if (latest != null) {
                Button(
                    onClick = { startInstall() },
                    enabled = !working,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.Download, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (working) "處理中…" else "下載並安裝")
                }
                if (working) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("${(progress * 100).toInt()}%")
                    }
                }
            } else {
                Text("此資料源沒有可安裝的 APK")
            }

            status?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(20.dp))

            if (app.summary.isNotBlank()) {
                Text("簡介", style = MaterialTheme.typography.titleSmall)
                Text(app.summary, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
            }

            if (app.description.isNotBlank()) {
                Text("說明", style = MaterialTheme.typography.titleSmall)
                Text(app.description, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
            }

            if (app.versions.size > 1) {
                Text("所有版本（${app.versions.size}）", style = MaterialTheme.typography.titleSmall)
                app.versions.take(10).forEach { v ->
                    Text(
                        "• ${v.versionName}（${v.versionCode}）· ${RepoStore.formatSize(v.size)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(12.dp))
            }

            DetailLine("授權", app.license)
            DetailLine("作者", app.author)
            DetailLine("分類", app.categories.joinToString("、"))
            if (app.webSite.isNotBlank()) {
                LinkLine("網站", app.webSite)
            }
            if (app.sourceCode.isNotBlank()) {
                LinkLine("原始碼", app.sourceCode)
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun LinkLine(label: String, url: String) {
    val context = LocalContext.current
    Text(
        "$label：$url",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .padding(vertical = 2.dp)
            .clickable { RepoStore.openUrl(context, url) }
    )
}

@Composable
private fun DetailLine(label: String, value: String) {
    if (value.isBlank()) return
    Row(Modifier.padding(vertical = 2.dp)) {
        Text("$label：", style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}
