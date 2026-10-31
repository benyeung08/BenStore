package com.ainovelapp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ainovelapp.ui.screens.LibraryScreen
import com.ainovelapp.ui.screens.ModelScreen
import com.ainovelapp.ui.screens.StudioScreen
import com.ainovelapp.ui.theme.AiNovelAppTheme

class MainActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        // Android 16（API 36）起 edge-to-edge 為強制行為，這裡明確啟用並自行處理系統列
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            AiNovelAppTheme {
                val widthSizeClass = calculateWindowSizeClass(this).widthSizeClass
                AppRoot(widthSizeClass = widthSizeClass)
            }
        }
    }
}

private enum class Tab(val labelRes: Int, val icon: ImageVector) {
    STUDIO(R.string.tab_studio, Icons.Default.Edit),
    LIBRARY(R.string.tab_library, Icons.AutoMirrored.Filled.MenuBook),
    MODEL(R.string.tab_model, Icons.Default.Memory)
}

@Composable
private fun AppRoot(
    widthSizeClass: WindowWidthSizeClass,
    vm: NovelViewModel = viewModel()
) {
    // Android 17 大螢幕上方向與尺寸限制一律被忽略，介面必須自己適應。
    // 這裡依視窗寬度切換「底部導覽 / 側邊導覽」。
    val useRail = widthSizeClass != WindowWidthSizeClass.Compact
    var selected by rememberSaveable { mutableIntStateOf(0) }

    RequestNotificationPermissionIfNeeded()

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            val (_, text) = vm.exportNovel()
            try {
                vm.appContext.contentResolver.openOutputStream(uri)?.use {
                    it.write(text.toByteArray())
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    val content: @Composable (Modifier) -> Unit = { modifier ->
        when (selected) {
            0 -> StudioScreen(vm, modifier)
            1 -> LibraryScreen(
                vm = vm,
                onExport = { fileName, _ -> exportLauncher.launch(fileName) },
                modifier = modifier
            )
            else -> ModelScreen(vm, modifier)
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        if (useRail) {
            Row(modifier = Modifier.fillMaxSize()) {
                NavigationRail {
                    Tab.entries.forEachIndexed { index, tab ->
                        NavigationRailItem(
                            selected = selected == index,
                            onClick = { selected = index },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(androidx.compose.ui.res.stringResource(tab.labelRes)) }
                        )
                    }
                }
                content(Modifier.fillMaxSize())
            }
        } else {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                bottomBar = {
                    NavigationBar {
                        Tab.entries.forEachIndexed { index, tab ->
                            NavigationBarItem(
                                selected = selected == index,
                                onClick = { selected = index },
                                icon = { Icon(tab.icon, contentDescription = null) },
                                label = { Text(androidx.compose.ui.res.stringResource(tab.labelRes)) }
                            )
                        }
                    }
                }
            ) { innerPadding ->
                content(Modifier.padding(innerPadding))
            }
        }
    }
}

/** Android 13+ 顯示常駐通知需要權限；沒給也不影響生成，只是看不到通知。 */
@Composable
private fun RequestNotificationPermissionIfNeeded() {
    if (Build.VERSION.SDK_INT < 33) return
    val context = androidx.compose.ui.platform.LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 被拒絕就只用 App 內進度條 */ }

    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
