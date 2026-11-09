package com.kaiser.rivet.local

import kotlin.math.roundToInt

/** The safe working set from the local-models spec; all RAM values are MiB. */
data class SafeCeiling(val maxParamsB: Double, val contextMax: Int, val label: String)

enum class FitBadge { BestFit, RunsFine, Heavy, TooBig }

data class ModelFit(
    val entry: LocalModelEntry,
    val badge: FitBadge,
    val reason: String,
) {
    val canDownload: Boolean get() = badge != FitBadge.TooBig
}

data class Recommendation(
    val primary: LocalModelEntry?,
    val alternative: LocalModelEntry?,
    val why: String,
) {
    val options: List<LocalModelEntry> get() = listOfNotNull(primary, alternative).distinctBy { it.id }
}

object ModelRecommender {
    private const val MIB = 1024L * 1024L
    private const val RESERVE_OVERHEAD_BYTES = 384L * MIB
    private const val KV_BYTES_PER_CONTEXT_TOKEN = 16L * 1024L
    private const val AVAILABLE_HEADROOM = 0.90

    fun ceilingFor(totalRamMb: Int): SafeCeiling = when {
        totalRamMb >= 12_288 -> SafeCeiling(8.0, 16_384, "12 GB or more")
        totalRamMb >= 8_192 -> SafeCeiling(4.0, 16_384, "8 GB")
        totalRamMb >= 6_144 -> SafeCeiling(2.0, 8_192, "6 GB")
        totalRamMb >= 4_096 -> SafeCeiling(1.0, 4_096, "4 GB")
        else -> SafeCeiling(0.0, 2_048, "under 4 GB")
    }

    fun estimatedNeedBytes(entry: LocalModelEntry): Long =
        entry.size + entry.contextMax.toLong() * KV_BYTES_PER_CONTEXT_TOKEN + RESERVE_OVERHEAD_BYTES

    fun fit(entry: LocalModelEntry, totalRamMb: Int, availableBytes: Long): ModelFit {
        val ceiling = ceilingFor(totalRamMb)
        val why = when {
            entry.ramFloor > totalRamMb ->
                "This model asks for at least ${entry.ramFloor / 1024} GB of phone RAM."
            entry.paramsB > ceiling.maxParamsB + 0.01 ->
                "This phone's safe ceiling is about ${ceiling.maxParamsB.asParams()} at ${ceiling.label}."
            entry.size > totalRamMb.toLong() * MIB * 65 / 100 ->
                "This model file is larger than this phone should hold in memory."
            estimatedNeedBytes(entry) > (availableBytes.coerceAtLeast(0L) * AVAILABLE_HEADROOM).toLong() ->
                "It needs about ${formatGb(estimatedNeedBytes(entry))}, with only ${formatGb(availableBytes)} free right now."
            else -> ""
        }
        val badge = when {
            why.isNotBlank() && why.contains("ceiling") -> FitBadge.TooBig
            why.isNotBlank() && why.contains("larger") -> FitBadge.TooBig
            why.isNotBlank() && why.contains("at least") -> FitBadge.TooBig
            why.isNotBlank() -> FitBadge.Heavy
            else -> FitBadge.RunsFine
        }
        return ModelFit(entry, badge, why)
    }

    fun assess(entries: List<LocalModelEntry>, totalRamMb: Int, availableBytes: Long): List<ModelFit> {
        val fits = entries.map { fit(it, totalRamMb, availableBytes) }
        val recommendation = recommend(entries, totalRamMb, availableBytes)
        return fits.map { fit ->
            if (recommendation.primary?.id == fit.entry.id && fit.badge == FitBadge.RunsFine) {
                fit.copy(badge = FitBadge.BestFit, reason = recommendation.why)
            } else fit
        }
    }

    fun recommend(entries: List<LocalModelEntry>, totalRamMb: Int, availableBytes: Long): Recommendation {
        val ceiling = ceilingFor(totalRamMb)
        val coding = entries.filter {
            LocalCategory.Coding in it.categories && it.paramsB <= ceiling.maxParamsB + 0.01 &&
                it.ramFloor <= totalRamMb
        }
        val pool = coding.ifEmpty {
            entries.filter { it.paramsB <= ceiling.maxParamsB + 0.01 && it.ramFloor <= totalRamMb }
        }
        if (pool.isEmpty()) {
            return Recommendation(null, null,
                "This phone is below the 4 GB minimum for the local model catalogue.")
        }
        val usable = pool.filter { estimatedNeedBytes(it) <= (availableBytes * AVAILABLE_HEADROOM).toLong() }
        val byRam = pool.sortedWith(compareByDescending<LocalModelEntry> { it.paramsB })
        val primary = (usable.ifEmpty { byRam }).maxByOrNull { it.paramsB }!!
        val alternative = byRam
            .filter { it.id != primary.id && it.paramsB <= primary.paramsB * 0.6 }
            .maxByOrNull { it.paramsB }
            ?: byRam
                .filter { it.id != primary.id && it.paramsB > primary.paramsB }
                .minByOrNull { it.paramsB }
            ?: byRam.firstOrNull { it.id != primary.id }
        val why = when {
            usable.isEmpty() && availableBytes > 0 ->
                "The closest safe fit right now; close other apps before starting it."
            primary.paramsB < byRam.first().paramsB ->
                "More free RAM right now than the largest model needs."
            else -> "Largest coding model that fits this phone's safe ceiling."
        }
        return Recommendation(primary, alternative, why)
    }

    private fun Double.asParams(): String =
        if (this % 1.0 == 0.0) "${toInt()}B" else "${(this * 10).roundToInt() / 10.0}B"

    private fun formatGb(bytes: Long): String =
        String.format(java.util.Locale.US, "%.1f GB", bytes / 1_000_000_000.0)
}
