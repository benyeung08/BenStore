package com.ainovelapp

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ainovelapp.data.Chapter
import com.ainovelapp.data.GenerationConfig
import com.ainovelapp.data.ModelState
import com.ainovelapp.data.Novel
import com.ainovelapp.data.VulkanInfo
import com.ainovelapp.data.NovelRepository
import com.ainovelapp.engine.GenerationService
import com.ainovelapp.util.DeviceCapabilities
import com.ainovelapp.util.PromptBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class NovelViewModel(app: Application) : AndroidViewModel(app) {

    /** 供 Activity 存取 ContentResolver（匯出 TXT 用） */
    val appContext: Context get() = getApplication<Application>()

    private val repo = NovelRepository(app)

    /** llama.cpp 引擎 handle，0 表示尚未載入 */
    private var engineHandle: Long = 0

    private val _modelState = MutableStateFlow<ModelState>(ModelState.Idle)
    val modelState: StateFlow<ModelState> = _modelState.asStateFlow()

    private val _config = MutableStateFlow(GenerationConfig())
    val config: StateFlow<GenerationConfig> = _config.asStateFlow()

    private val _novels = MutableStateFlow(repo.novels())
    val novels: StateFlow<List<Novel>> = _novels.asStateFlow()

    private val _currentNovel = MutableStateFlow(repo.novels().firstOrNull())
    val currentNovel: StateFlow<Novel?> = _currentNovel.asStateFlow()

    private val _chapters = MutableStateFlow<List<Chapter>>(emptyList())
    val chapters: StateFlow<List<Chapter>> = _chapters.asStateFlow()

    private val _generatingText = MutableStateFlow("")
    val generatingText: StateFlow<String> = _generatingText.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _status = MutableStateFlow("尚未載入模型")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _vulkanInfo = MutableStateFlow(VulkanInfo.None)
    val vulkanInfo: StateFlow<VulkanInfo> = _vulkanInfo.asStateFlow()

    /** 目前模型實際跑在哪個後端（"Vulkan" / "CPU"），供 UI 顯示 */
    private val _activeBackend = MutableStateFlow("未載入")
    val activeBackend: StateFlow<String> = _activeBackend.asStateFlow()

    init {
        LlamaBridge.nativeBackendInit()
        val nCores = Runtime.getRuntime().availableProcessors()
        _config.update { it.copy(threads = nCores.coerceIn(3, 6)) }
        _currentNovel.value?.let { _chapters.value = repo.chaptersOf(it.id) }
        refreshVulkanInfo()
    }

    /** 重新探測 Vulkan 裝置。裝置不支援時自動關閉 Vulkan 選項。 */
    fun refreshVulkanInfo() {
        viewModelScope.launch(Dispatchers.Default) {
            val info = parseVulkanInfo(LlamaBridge.nativeVulkanInfo())
            _vulkanInfo.value = info
            if (!info.available && _config.value.useVulkan) {
                _config.update { it.copy(useVulkan = false) }
                _status.value = "此裝置不支援 Vulkan，已切換為 CPU"
            }
        }
    }

    private fun parseVulkanInfo(raw: String): VulkanInfo {
        if (raw.isBlank() || raw == "none") return VulkanInfo.None
        val parts = raw.split("|")
        val name = parts.getOrNull(0)?.trim().orEmpty()
        val version = parts.getOrNull(1)?.trim().orEmpty()
        if (name.isEmpty()) return VulkanInfo.None
        // llama.cpp 的 Vulkan 後端實務上需要 1.1+，1.0 裝置容易踩驅動 bug
        val major = version.substringBefore('.').toIntOrNull() ?: 1
        val minor = version.substringAfter('.').substringBefore('.').toIntOrNull() ?: 0
        val usable = major > 1 || (major == 1 && minor >= 1)
        return VulkanInfo(
            available = usable,
            deviceName = name,
            apiVersion = version
        )
    }

    override fun onCleared() {
        if (engineHandle != 0L) LlamaBridge.nativeFreeModel(engineHandle)
        LlamaBridge.nativeBackendFree()
        super.onCleared()
    }

    // ------------------------------------------------------------------
    // 模型管理
    // ------------------------------------------------------------------

    /**
     * 從 SAF 選擇的 URI 匯入 GGUF。llama.cpp 只能吃真實檔案路徑，
     * 所以先複製到 app 私有目錄（1GB 左右的檔案約需數十秒）。
     */
    fun importModel(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "model.gguf"
            _modelState.value = ModelState.Loading(name, 0f)
            _status.value = "正在複製模型檔案…"

            val dir = File(ctx.filesDir, "models").apply { mkdirs() }
            val target = File(dir, name)
            try {
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output ->
                        val buf = ByteArray(1024 * 1024)
                        var total = 0L
                        var read: Int
                        while (input.read(buf).also { read = it } != -1) {
                            output.write(buf, 0, read)
                            total += read
                            _modelState.value = ModelState.Loading(name, total / 1_000_000_000f)
                        }
                    }
                } ?: throw IllegalStateException("無法讀取選擇的檔案")
            } catch (e: Exception) {
                _modelState.value = ModelState.Error("複製失敗：${e.message}")
                _status.value = "匯入失敗"
                return@launch
            }

            val cfg = _config.value
            _status.value = if (cfg.useVulkan) "正在載入模型到 GPU（Vulkan）…"
                            else "正在載入模型到記憶體…"

            // Android 17 對記憶體超限的行程是直接終止，先把關再載，
            // 免得載到一半整支 App 被系統砍掉。
            val sizeBytes = target.length()
            if (!DeviceCapabilities.canLoadModel(ctx, sizeBytes, cfg.contextSize)) {
                val need = DeviceCapabilities.estimateNeedMb(sizeBytes, cfg.contextSize)
                val avail = DeviceCapabilities.memoryInfo(ctx).availableMb
                _modelState.value = ModelState.Error(
                    "可用記憶體不足：約需 ${need} MB，目前剩 ${avail} MB。" +
                            "請調小上下文長度或換更小的模型。"
                )
                _status.value = "載入中止（記憶體不足）"
                return@launch
            }

            val handle = withContext(Dispatchers.Default) {
                LlamaBridge.nativeLoadModel(
                    target.absolutePath,
                    cfg.contextSize,
                    cfg.threads,
                    cfg.effectiveGpuLayers()
                )
            }
            if (handle == 0L) {
                _modelState.value = ModelState.Error(
                    "模型載入失敗，可能是檔案損毀或不支援的架構。" +
                            "若裝置為 16 KB 記憶體頁，請確認 llama.cpp 版本已支援。"
                )
                _status.value = "載入失敗"
            } else {
                if (engineHandle != 0L) LlamaBridge.nativeFreeModel(engineHandle)
                engineHandle = handle
                val backendRaw = LlamaBridge.nativeGetActiveBackend(handle)
                val backendName = backendRaw.substringBefore(' ')
                val loadedLayers = backendRaw.substringAfter(' ').toIntOrNull() ?: 0
                _activeBackend.value = if (backendName == "Vulkan") "Vulkan（$loadedLayers 層）" else "CPU"
                _modelState.value = ModelState.Ready(
                    fileName = name,
                    path = target.absolutePath,
                    backend = backendName,
                    gpuLayers = loadedLayers
                )
                _status.value = if (backendName == "Vulkan") {
                    "已載入：$name ｜ Vulkan $loadedLayers 層"
                } else {
                    if (cfg.useVulkan) "已載入：$name ｜ Vulkan 失敗已退回 CPU"
                    else "已載入：$name ｜ CPU"
                }
            }
        }
    }

    fun unloadModel() {
        if (engineHandle != 0L) {
            LlamaBridge.nativeFreeModel(engineHandle)
            engineHandle = 0L
        }
        _modelState.value = ModelState.Idle
        _activeBackend.value = "未載入"
        _status.value = "模型已卸載"
    }

    fun updateConfig(cfg: GenerationConfig) {
        _config.value = cfg
    }

    // ------------------------------------------------------------------
    // 書庫
    // ------------------------------------------------------------------

    fun createNovel(title: String) {
        val novel = Novel(
            id = UUID.randomUUID().toString(),
            title = title.ifBlank { "未命名小說" }
        )
        repo.saveNovel(novel)
        _currentNovel.value = novel
        _chapters.value = emptyList()
        _novels.value = repo.novels()
        _generatingText.value = ""
    }

    fun selectNovel(novel: Novel) {
        _currentNovel.value = novel
        _chapters.value = repo.chaptersOf(novel.id)
        _generatingText.value = ""
    }

    fun deleteNovel(novel: Novel) {
        repo.deleteNovel(novel.id)
        _novels.value = repo.novels()
        if (_currentNovel.value?.id == novel.id) {
            _currentNovel.value = null
            _chapters.value = emptyList()
        }
    }

    fun updateNovelMeta(
        genre: String, style: String, premise: String, characters: String, viewpoint: String
    ) {
        val novel = _currentNovel.value ?: return
        val updated = novel.copy(
            genre = genre, style = style, premise = premise,
            characters = characters, viewpoint = viewpoint
        )
        repo.saveNovel(updated)
        _currentNovel.value = updated
        _novels.value = repo.novels()
    }

    fun exportNovel(): Pair<String, String> {
        val novel = _currentNovel.value ?: return "" to ""
        return novel.title to repo.exportText(novel.id)
    }

    fun deleteChapter(chapter: Chapter) {
        val novel = _currentNovel.value ?: return
        repo.deleteChapter(novel.id, chapter.index)
        _chapters.value = repo.chaptersOf(novel.id)
    }

    // ------------------------------------------------------------------
    // 生成
    // ------------------------------------------------------------------

    private fun ensureEngine(): Boolean {
        if (engineHandle == 0L) {
            _status.value = "請先在「模型」分頁載入 GGUF 模型"
            return false
        }
        return true
    }

    /** 生成大綱 */
    fun generateOutline(chapterCount: Int = 10) {
        val novel = _currentNovel.value ?: run {
            _status.value = "請先建立一本小說"
            return
        }
        runGeneration(PromptBuilder.outline(novel, chapterCount)) { text ->
            val cleaned = text.trim()
            val updated = novel.copy(outline = cleaned)
            repo.saveNovel(updated)
            _currentNovel.value = updated
            _novels.value = repo.novels()
        }
    }

    /** 寫下一章 */
    fun writeNextChapter(words: Int = 800) {
        val novel = _currentNovel.value ?: run {
            _status.value = "請先建立一本小說"
            return
        }
        val existing = repo.chaptersOf(novel.id)
        val index = (existing.maxOfOrNull { it.index } ?: 0) + 1
        val tail = existing.maxByOrNull { it.index }?.content?.takeLast(700) ?: ""
        val title = novel.outline.lines()
            .firstOrNull { it.contains("第${index}章") }
            ?.substringAfter("｜")
            ?.substringBefore("：")
            ?.trim()
            ?: ""

        runGeneration(PromptBuilder.writeChapter(novel, index, title, tail, words)) { text ->
            val chapter = Chapter(
                id = UUID.randomUUID().toString(),
                novelId = novel.id,
                index = index,
                title = title.ifBlank { "第 $index 章" },
                content = text.trim()
            )
            repo.saveChapter(chapter)
            _chapters.value = repo.chaptersOf(novel.id)
        }
    }

    /** 續寫最後一章 */
    fun continueLastChapter(words: Int = 500) {
        val novel = _currentNovel.value ?: return
        val last = repo.chaptersOf(novel.id).maxByOrNull { it.index } ?: run {
            writeNextChapter(words)
            return
        }
        runGeneration(PromptBuilder.continueChapter(novel, last.content, words)) { text ->
            val merged = last.content + "\n" + text.trim()
            val updated = last.copy(content = merged)
            repo.saveChapter(updated)
            _chapters.value = repo.chaptersOf(novel.id)
        }
    }

    /** 自由指令：使用者自己在輸入框寫要做什麼 */
    fun runFreePrompt(instruction: String) {
        val novel = _currentNovel.value
        val tail = repo.chaptersOf(novel?.id ?: "").maxByOrNull { it.index }?.content?.takeLast(700) ?: ""
        val prompt = PromptBuilder.freeform(novel, instruction, tail)
        runGeneration(prompt) { text ->
            val n = novel ?: return@runGeneration
            val existing = repo.chaptersOf(n.id)
            val index = (existing.maxOfOrNull { it.index } ?: 0) + 1
            repo.saveChapter(
                Chapter(
                    id = UUID.randomUUID().toString(),
                    novelId = n.id,
                    index = index,
                    title = "自由創作 $index",
                    content = text.trim()
                )
            )
            _chapters.value = repo.chaptersOf(n.id)
        }
    }

    private fun runGeneration(prompt: String, keepHistory: Boolean = false, onFinished: (String) -> Unit) {
        if (!ensureEngine() || _isGenerating.value) return
        val cfg = _config.value
        _isGenerating.value = true
        _generatingText.value = ""
        _status.value = "生成中…"

        // 長時間滿載推理由前台服務撐住，避免被 Android 17 的
        // KILL_EXCESSIVE_CPU_USAGE / 記憶體上限機制終止
        GenerationService.start(appContext)

        viewModelScope.launch(Dispatchers.Default) {
            val buffer = StringBuilder()
            var lastNotify = 0L
            val callback = object : GenerationCallback() {
                override fun onToken(text: String): Boolean {
                    buffer.append(text)
                    _generatingText.update { buffer.toString() }
                    // 每約 3 秒更新一次通知，不要太頻繁
                    val now = System.currentTimeMillis()
                    if (now - lastNotify > 3000) {
                        lastNotify = now
                        GenerationService.update(appContext, "已生成 ${buffer.length} 字…")
                    }
                    return !Thread.currentThread().isInterrupted
                }
            }
            val n = LlamaBridge.nativeGenerate(
                engineHandle,
                prompt,
                cfg.maxTokens,
                cfg.temperature,
                cfg.topP,
                cfg.topK,
                cfg.repeatPenalty,
                callback,
                keepHistory
            )
            val result = buffer.toString()
            withContext(Dispatchers.Main) {
                _isGenerating.value = false
                _status.value = if (n >= 0) "完成，共 $n tokens" else "生成失敗（錯誤碼 $n）"
                GenerationService.stop(appContext)
                if (result.isNotBlank()) onFinished(result)
            }
        }
    }

    fun stopGeneration() {
        if (engineHandle != 0L) LlamaBridge.nativeStop(engineHandle)
        GenerationService.stop(appContext)
        _isGenerating.value = false
        _status.value = "已停止"
    }

    // ------------------------------------------------------------------
    // 裝置資訊（Android 17 / 16 KB 頁相容性）
    // ------------------------------------------------------------------

    private val _deviceInfo = MutableStateFlow(loadDeviceInfo())
    val deviceInfo: StateFlow<DeviceInfo> = _deviceInfo.asStateFlow()

    private fun loadDeviceInfo() = DeviceInfo(
        pageSizeLabel = DeviceCapabilities.pageSizeLabel(),
        is16Kb = DeviceCapabilities.is16KbPageDevice(),
        memory = DeviceCapabilities.memoryInfo(appContext)
    )

    fun refreshDeviceInfo() {
        _deviceInfo.value = loadDeviceInfo()
    }
}

data class DeviceInfo(
    val pageSizeLabel: String,
    val is16Kb: Boolean,
    val memory: DeviceCapabilities.MemoryInfo
)
