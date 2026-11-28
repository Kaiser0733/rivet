package com.kaiser.rivet.browser

/**
 * Pure browser budget arithmetic and tier decisions.
 *
 * The renderer PSS is not visible to an in-app process (plan §1.1), so this object
 * never pretends to measure it. It computes the budget from device headroom and the
 * local model's measured own-process footprint, then decides behaviour.
 */
enum class BrowserTier {
    Normal,
    StrictDiet,
    ReaderFirst,
    NoRenderer,
}

enum class BrowserShedStep {
    Caches,
    SnapshotInactive,
    TightenDiet,
    Reader,
}

data class BrowserMemorySignals(
    val availableRamBytes: Long,
    val modelFootprintBytes: Long = 0L,
    val trimLevel: Int = 0,
    val rendererActive: Boolean = false,
    /** Measured growth of this app and, where available, its renderer. */
    val observedBrowserBytes: Long = 0L,
    /** The live page is a loopback preview: the memory cap does not apply. */
    val loopbackLive: Boolean = false,
)

private const val BYTES_PER_MIB = 1024L * 1024L

enum class BrowserMemoryLimit(val megabytes: Int, val label: String) {
    Mb250(250, "250 MB"),
    Gb2(2048, "2 GB");

    val bytes: Long get() = megabytes * BYTES_PER_MIB
}

data class BrowserBudget(
    val capBytes: Long,
    val tier: BrowserTier,
    val reserveBytes: Long,
    val headroomBytes: Long,
    val modelLoaded: Boolean,
    val observedBrowserBytes: Long = 0L,
    val loopbackExempt: Boolean = false,
) {
    val capMb: Int get() = if (loopbackExempt) 0 else (capBytes / BYTES_PER_MIB).toInt()
    val observedMb: Int get() = (observedBrowserBytes / BYTES_PER_MIB).toInt()
}


data class BrowserTierInfo(
    val wireName: String,
    val rendererAllowed: Boolean,
    val screenshotsAllowed: Boolean,
    val strictDiet: Boolean,
    val singleStepTeardown: Boolean,
    val summary: String,
)

object BrowserGovernor {
    const val SAFETY_BYTES = 300L * 1024 * 1024
    const val WORKING_MARGIN_BYTES = 150L * 1024 * 1024
    const val MIB = BYTES_PER_MIB

    fun decide(signals: BrowserMemorySignals, limit: BrowserMemoryLimit = BrowserMemoryLimit.Gb2): BrowserBudget {
        if (signals.loopbackLive) {
            return BrowserBudget(Long.MAX_VALUE, BrowserTier.Normal, 0L, 0L,
                signals.modelFootprintBytes > 0L, 0L, loopbackExempt = true)
        }
        val model = signals.modelFootprintBytes.coerceAtLeast(0L)
        val reserve = model + SAFETY_BYTES
        val headroom = (signals.availableRamBytes.coerceAtLeast(0L) - reserve).coerceAtLeast(0L)
        val cap = (headroom - WORKING_MARGIN_BYTES).coerceIn(0L, limit.bytes)
        val observed = signals.observedBrowserBytes.coerceAtLeast(0L)
        val tier = moreSevere(tierFor(cap), observedTier(observed, limit))
        return BrowserBudget(cap, tier, reserve, headroom, model > 0L, observed)
    }

    fun observedTier(bytes: Long, limit: BrowserMemoryLimit): BrowserTier = when {
        bytes >= limit.bytes -> BrowserTier.NoRenderer
        bytes >= limit.bytes * 80 / 100 -> BrowserTier.ReaderFirst
        bytes >= limit.bytes * 48 / 100 -> BrowserTier.StrictDiet
        else -> BrowserTier.Normal
    }

    private fun moreSevere(a: BrowserTier, b: BrowserTier): BrowserTier =
        if (a.ordinal >= b.ordinal) a else b

    /** Boundaries are inclusive on the lower end: 200 normal, 120 strict, 60 reader. */
    fun tierFor(capBytes: Long): BrowserTier = when {
        capBytes >= 200L * MIB -> BrowserTier.Normal
        capBytes >= 120L * MIB -> BrowserTier.StrictDiet
        capBytes >= 60L * MIB -> BrowserTier.ReaderFirst
        else -> BrowserTier.NoRenderer
    }

    fun canOpenRenderer(tier: BrowserTier, interaction: Boolean): Boolean = when (tier) {
        BrowserTier.Normal, BrowserTier.StrictDiet, BrowserTier.ReaderFirst -> interaction
        BrowserTier.NoRenderer -> false
    }

    /**
     * Agent-facing explanation of a tier. Capability booleans come from the same
     * gates the renderer enforces, so a tool description cannot drift from behaviour.
     */
    fun tierGuide(tier: BrowserTier, loopbackExempt: Boolean): BrowserTierInfo {
        val wire = when (tier) {
            BrowserTier.Normal -> "normal"
            BrowserTier.StrictDiet -> "strictdiet"
            BrowserTier.ReaderFirst -> "readerfirst"
            BrowserTier.NoRenderer -> "norenderer"
        }
        val summary = when {
            tier == BrowserTier.Normal && loopbackExempt ->
                "Loopback preview: memory cap paused; full renderer, JS, and screenshots " +
                    "active (hang safety valve only)."
            tier == BrowserTier.Normal ->
                "normal: full renderer and screenshots available."
            tier == BrowserTier.StrictDiet ->
                "strictdiet: heavy images replaced with 1x1 " +
                    "placeholders (HTML/JS unaffected); screenshots allowed; inactive " +
                    "tabs may unload."
            tier == BrowserTier.ReaderFirst ->
                "readerfirst: renderer opens for one " +
                    "interaction step then tears down; browser_shot blocked; prefer " +
                    "browser_read."
            else ->
                "norenderer: visual renderer and " +
                    "screenshots blocked; browser_read/browser_find text mode only, or " +
                    "open in phone browser."
        }
        return BrowserTierInfo(
            wireName = wire,
            rendererAllowed = canOpenRenderer(tier, interaction = true),
            screenshotsAllowed = canTakeScreenshot(tier),
            strictDiet = tier == BrowserTier.StrictDiet,
            singleStepTeardown = tier == BrowserTier.ReaderFirst,
            summary = summary,
        )
    }

    fun canTakeScreenshot(tier: BrowserTier): Boolean =
        tier == BrowserTier.Normal || tier == BrowserTier.StrictDiet

    fun nextShed(step: BrowserShedStep?): BrowserShedStep? = when (step) {
        null -> BrowserShedStep.Caches
        BrowserShedStep.Caches -> BrowserShedStep.SnapshotInactive
        BrowserShedStep.SnapshotInactive -> BrowserShedStep.TightenDiet
        BrowserShedStep.TightenDiet -> BrowserShedStep.Reader
        BrowserShedStep.Reader -> null
    }

    fun shedPlan(): List<BrowserShedStep> = listOf(
        BrowserShedStep.Caches,
        BrowserShedStep.SnapshotInactive,
        BrowserShedStep.TightenDiet,
        BrowserShedStep.Reader,
    )

    fun contextLine(budget: BrowserBudget): String {
        if (budget.loopbackExempt) {
            return "Browser: loopback preview, memory cap paused (${budget.observedMb}MB used)."
        }
        val tier = when (budget.tier) {
            BrowserTier.Normal -> "normal"
            BrowserTier.StrictDiet -> "strict-diet"
            BrowserTier.ReaderFirst -> "reader-first"
            BrowserTier.NoRenderer -> "reader-only"
        }
        val suffix = if (budget.modelLoaded) " (local model loaded)" else ""
        return "Browser: $tier, ${budget.observedMb}MB used, ${budget.capMb}MB cap$suffix."
    }

    fun refusalReason(budget: BrowserBudget): String = when (budget.tier) {
        BrowserTier.NoRenderer ->
            "The browser renderer is paused: ${budget.capMb}MB is available. Reader mode works; open the page in your browser for interaction."
        BrowserTier.ReaderFirst ->
            "The browser is in reader-first mode (${budget.capMb}MB). The renderer opens only for a single interaction step."
        else -> "The browser budget is ${budget.capMb}MB."
    }
}
