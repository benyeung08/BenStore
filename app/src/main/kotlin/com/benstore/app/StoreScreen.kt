package com.benstore.app

import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

sealed class StoreState {
    object Loading : StoreState()
    data class Ready(val index: RepoIndex) : StoreState()
    data class Error(val message: String) : StoreState()
}

private val PRESET_REPOS = listOf(
    "F-Droid" to "https://f-droid.org/repo",
    "IzzyOnDroid" to "https://apt.izzysoft.de/fdroid/repo"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoreApp(startRepo: String = RepoStore.DEFAULT_REPO) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var repoUrl by remember { mutableStateOf(startRepo) }
    var state by remember { mutableStateOf<StoreState>(StoreState.Loading) }
    var detailPackage by remember { mutableStateOf<String?>(null) }

    fun reload(force: Boolean) {
        scope.launch {
            state = StoreState.Loading
            state = try {
                StoreState.Ready(RepoStore.load(context, repoUrl, force))
            } catch (t: Throwable) {
                StoreState.Error(t.message ?: "載入失敗")
            }
        }
    }

    LaunchedEffect(repoUrl) { reload(false) }

    when (val s = state) {
        StoreState.Loading -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text("正在同步倉庫索引…")
                }
            }
        }

        is StoreState.Error -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("載入失敗：${s.message}")
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { reload(true) }) { Text("重試") }
                }
            }
        }

        is StoreState.Ready -> {
            val index = s.index
            val selected = detailPackage?.let { pkg ->
                index.apps.firstOrNull { it.packageName == pkg }
            }
            if (selected != null) {
                BackHandler { detailPackage = null }
                AppDetailScreen(app = selected, index = index, onBack = { detailPackage = null })
            } else {
                AppListScreen(
                    index = index,
                    repoUrl = repoUrl,
                    onRepoChange = { repoUrl = it },
                    onRefresh = { reload(true) },
                    onOpen = { detailPackage = it.packageName }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppListScreen(
    index: RepoIndex,
    repoUrl: String,
    onRepoChange: (String) -> Unit,
    onRefresh: () -> Unit,
    onOpen: (AppInfo) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<String?>(null) }

    val categories = remember(index) {
        index.apps.asSequence()
            .flatMap { it.categories.asSequence() }
            .groupingBy { it }
            .eachCount()
            .toList()
            .sortedByDescending { it.second }
            .take(12)
            .map { it.first }
    }

    val filtered = remember(index, query, category) {
        val q = query.trim().lowercase()
        index.apps.filter { app ->
            (category == null || app.categories.contains(category)) &&
                (q.isBlank() ||
                    app.name.lowercase().contains(q) ||
                    app.summary.lowercase().contains(q) ||
                    app.packageName.lowercase().contains(q))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("${index.name} · ${index.apps.size} 個應用") },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = "重新同步")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding)) {
            Row(
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                PRESET_REPOS.forEach { (label, url) ->
                    FilterChip(
                        selected = repoUrl == url,
                        onClick = { onRepoChange(url) },
                        label = { Text(label) },
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
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

@Composable
private fun AppIcon(app: AppInfo, size: Int) {
    val bitmap = remember(app.iconName) {
        try {
            RepoStore.iconBitmap(app.iconName)
        } catch (t: Throwable) {
            null
        }
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
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
private fun AppRow(app: AppInfo, onClick: () -> Unit) {
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
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppDetailScreen(app: AppInfo, index: RepoIndex, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val apk = remember(app) { index.suggestedApk(app) }

    var working by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var status by remember { mutableStateOf<String?>(null) }

    fun startInstall() {
        val target = apk ?: return
        if (!RepoStore.canInstall(context)) {
            RepoStore.openInstallPermissionSettings(context)
            return
        }
        scope.launch {
            working = true
            status = "下載中…"
            progress = 0f
            try {
                val file = RepoStore.downloadApk(context, target) { done, total ->
                    if (total > 0) progress = done.toFloat() / total.toFloat()
                }
                status = "下載完成，交給系統安裝器"
                RepoStore.installApk(context, file)
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
                        "版本 ${apk?.versionName ?: "未知"} · ${RepoStore.formatSize(apk?.size ?: 0L)}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            if (apk != null) {
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
                Text("此倉庫沒有可安裝的 APK")
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

            DetailLine("授權", app.license)
            DetailLine("作者", app.author)
            DetailLine("分類", app.categories.joinToString("、"))
            if (app.sourceCode.isNotBlank()) {
                Text(
                    "原始碼：${app.sourceCode}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { RepoStore.openUrl(context, app.sourceCode) }
                )
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    if (value.isBlank()) return
    Row(Modifier.padding(vertical = 2.dp)) {
        Text("$label：", style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}
