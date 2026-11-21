package com.kaiser.rivet.storage

internal fun repriceContextUsage(reported: Long, sampledEstimate: Long, currentEstimate: Long): Long =
    try {
        Math.addExact(reported, currentEstimate - sampledEstimate).coerceAtLeast(0)
    } catch (_: ArithmeticException) {
        currentEstimate
    }
