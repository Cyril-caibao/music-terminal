package com.multitrack.app.audio.dfn3

import android.content.Context

/**
 * 把 assets 里的模型字节喂给 [Dfn3]。
 *
 * 单独抽一层是因为 [Dfn3] 本身不能依赖 Android 的 `Context` —— 它要能在 JVM 单测里
 * 直接跑（那是它唯一能被官方 `deep-filter` 交叉验证的地方）。
 *
 * 加载是**懒的**：不点「AI 降噪 · 人声」就不会去解 7.6MB 的 ONNX、也不会加载
 * onnxruntime 的原生库。加载失败（比如某些 ABI 没打进包）只记原因，不抛异常，
 * 调用方可以安静地退回统计掩码那条路。
 */
object Dfn3Assets {

    @Volatile
    private var appContext: Context? = null

    /** 初始化一次就够；重复调用无副作用 */
    fun install(context: Context) {
        appContext = context.applicationContext
    }

    /** 模型是否已就绪（已加载过） */
    val ready: Boolean get() = Dfn3.ready

    /** 上次失败原因，给 UI 用 */
    val lastError: String? get() = Dfn3.lastError

    /**
     * 确保三个图都加载好。
     *
     * @return true 表示可以走真模型；false 表示没装上下文或加载失败，
     *         调用方应退回统计降噪，并把 [lastError] 显示出来
     */
    fun prepare(): Boolean {
        if (Dfn3.ready) return true
        val ctx = appContext ?: run {
            return false
        }
        return Dfn3.prepare { name -> ctx.assets.open("dfn3/$name").use { it.readBytes() } }
    }
}
