package com.kaiser.rivet.ui.provider

internal fun matchingModelCount(matches: Int, shown: Int): String =
    "$matches matching ${if (matches == 1) "model" else "models"}. Showing $shown."
