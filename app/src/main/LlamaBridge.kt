package com.ainovelapp

/**
 * C++ 側逐字回調的目標類。
 * onToken 回傳 false 表示「到此為止」，C++ 會立刻跳出生成迴圈。
 */
abstract class GenerationCallback {
    abstract fun onToken(text: String): Boolean
}

/**
 * llama.cpp 的 JNI 封裝。所有方法都是阻塞式的，請在背景執行緒呼叫。
 *
 * 後端策略：Vulkan 為主（nGpuLayers = 99 全卸載），
 * 若裝置不支援或初始化失敗，C++ 側會自動退回純 CPU 重載。
 */
object LlamaBridge {

    init {
        System.loadLibrary("llama-bridge")
    }

    external fun nativeBackendInit()

    external fun nativeBackendFree()

    /**
     * 載入 GGUF，回傳引擎 handle，失敗回傳 0。
     * @param nGpuLayers 卸載到 GPU 的層數，99 = 全部（Vulkan），0 = 純 CPU
     */
    external fun nativeLoadModel(
        path: String,
        nCtx: Int,
        nThreads: Int,
        nGpuLayers: Int
    ): Long

    external fun nativeFreeModel(handle: Long)

    /** 清空 KV cache，等於忘掉前面的對話 */
    external fun nativeClearContext(handle: Long)

    /** 軟停止：讓正在跑的生成在下一個 token 處停下 */
    external fun nativeStop(handle: Long)

    /**
     * 探測 Vulkan 裝置，回傳 "裝置名|major.minor.patch"；
     * 不可用時回傳 "none"。
     */
    external fun nativeVulkanInfo(): String

    /** 實際生效的後端，例如 "Vulkan 28" 或 "CPU 0" */
    external fun nativeGetActiveBackend(handle: Long): String

    /**
     * 生成文字，回傳實際生成的 token 數（負值為錯誤碼）。
     * keepHistory = true 時不清空 KV cache，可用來做同一章的連續續寫。
     */
    external fun nativeGenerate(
        handle: Long,
        prompt: String,
        maxTokens: Int,
        temp: Float,
        topP: Float,
        topK: Int,
        repeatPenalty: Float,
        callback: GenerationCallback,
        keepHistory: Boolean
    ): Int
}
