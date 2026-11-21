package com.kaiser.rivet.ui.local

import com.kaiser.rivet.local.LocalModelEntry

internal fun uniqueModelEntries(
    recommendations: List<LocalModelEntry>,
    catalogue: List<LocalModelEntry>,
    installed: List<LocalModelEntry>,
    downloading: List<LocalModelEntry>,
): List<LocalModelEntry> = (recommendations + catalogue + installed + downloading).distinctBy { it.id }
