package com.kaiser.rivet.ui.chat

import com.kaiser.rivet.provider.ReasoningLevel

internal enum class EffortPattern { Line, Fork, Steps, Cross, Rings, Lattice }

internal data class EffortMotion(val pattern: EffortPattern, val duration: Int, val strength: Float)

internal fun effortMotion(level: ReasoningLevel): EffortMotion? = when (level) {
    ReasoningLevel.Default -> null
    ReasoningLevel.Low -> EffortMotion(EffortPattern.Line, 260, 0.48f)
    ReasoningLevel.Medium -> EffortMotion(EffortPattern.Fork, 300, 0.56f)
    ReasoningLevel.High -> EffortMotion(EffortPattern.Steps, 345, 0.64f)
    ReasoningLevel.XHigh -> EffortMotion(EffortPattern.Cross, 390, 0.72f)
    ReasoningLevel.Max -> EffortMotion(EffortPattern.Rings, 445, 0.80f)
    ReasoningLevel.Ultra -> EffortMotion(EffortPattern.Lattice, 500, 0.88f)
}
