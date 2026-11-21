package com.kaiser.rivet.ui.chat

internal const val CHOOSE_DIFFERENT_PROJECT_LABEL = "Choose a different project"

/**
 * The ready state is a fresh chat with a project already bound. It used to pass no
 * actions at all, so a phone that already had a project could reach neither the
 * project switcher nor the starter gallery from a new chat.
 */
internal data class ReadyEntryPoints(
    val chooseProjectLabel: String,
    val showStarters: Boolean,
)

internal fun readyEntryPoints(projectBound: Boolean): ReadyEntryPoints? =
    if (!projectBound) null
    else ReadyEntryPoints(CHOOSE_DIFFERENT_PROJECT_LABEL, showStarters = true)
