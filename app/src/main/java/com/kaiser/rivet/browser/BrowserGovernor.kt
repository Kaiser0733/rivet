package com.kaiser.rivet.browser

/**
 * Pure browser budget arithmetic and tier decisions.
 *
 * The renderer PSS is not visible to an in-app process (plan §1.1), so this object
 * never pretends to measure it. It computes the budget from device headroom and the
 * local model's measured own-process footprint, then decides behaviour. The memory
 * trace that proves the 250MB target is an adb acceptance item.
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
)

data class BrowserBudget(
    val capBytes: Long,
    val tier: BrowserTier,
    val reserveBytes: Long,
    val headroomBytes: Long,
    val modelLoaded: Boolean,
) {
    val capMb: Int get() = (capBytes / MIB).toInt()
}

object BrowserGovernor {
    const val MAX_BROWSER_BYTES = 250L * 1024 * 1024
    const val SAFETY_BYTES = 300L * 1024 * 1024
    const val WORKING_MARGIN_BYTES = 150L * 1024 * 1024
    const val MIB = 1024L * 1024L

    fun decide(signals: BrowserMemorySignals): BrowserBudget {
        val model = signals.modelFootprintBytes.coerceAtLeast(0L)
        val reserve = model + SAFETY_BYTES
        val headroom = (signals.availableRamBytes.coerceAtLeast(0L) - reserve).coerceAtLeast(0L)
        val cap = (headroom - WORKING_MARGIN_BYTES).coerceIn(0L, MAX_BROWSER_BYTES)
        return BrowserBudget(cap, tierFor(cap), reserve, headroom, model > 0L)
    }

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

    fun canUseReader(tier: BrowserTier): Boolean = true

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
        val tier = when (budget.tier) {
            BrowserTier.Normal -> "normal"
            BrowserTier.StrictDiet -> "strict-diet"
            BrowserTier.ReaderFirst -> "reader-first"
            BrowserTier.NoRenderer -> "reader-only"
        }
        val suffix = if (budget.modelLoaded) " (local model loaded)" else ""
        return "Browser: $tier, ${budget.capMb}MB cap$suffix."
    }

    fun refusalReason(budget: BrowserBudget): String = when (budget.tier) {
        BrowserTier.NoRenderer ->
            "The browser renderer is paused: ${budget.capMb}MB is available. Reader mode works; open the page in your browser for interaction."
        BrowserTier.ReaderFirst ->
            "The browser is in reader-first mode (${budget.capMb}MB). The renderer opens only for a single interaction step."
        else -> "The browser budget is ${budget.capMb}MB."
    }
}
