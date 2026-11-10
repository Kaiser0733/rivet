package com.kaiser.rivet.browser

import android.os.Debug

data class BrowserMemorySample(
    val ownPssBytes: Long,
    val rendererPssBytes: Long?,
)

/**
 * Own-process PSS is measurable in-app. Renderer PSS is not exposed on stock Android;
 * a caller may supply a platform probe, otherwise it stays null rather than fake.
 */
class BrowserMemoryProbe(
    private val ownPss: () -> Long = {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        info.totalPss.toLong() * 1024L
    },
    private val rendererPss: () -> Long? = { null },
) {
    fun sample(): BrowserMemorySample = BrowserMemorySample(
        ownPssBytes = ownPss().coerceAtLeast(0L),
        rendererPssBytes = rendererPss()?.coerceAtLeast(0L),
    )

    fun browserGrowthBytes(baselineOwnPss: Long, sample: BrowserMemorySample = sample()): Long =
        (sample.ownPssBytes - baselineOwnPss).coerceAtLeast(0L)
            .coerceAtLeast(sample.rendererPssBytes ?: 0L)
}
