package com.kaiser.rivet.ui

import com.kaiser.rivet.R

// Order is the tab order shown to the user; covered by a unit test so a
// careless reorder cannot ship silently.
enum class RivetDestination(val labelRes: Int, val emptyTextRes: Int, val iconRes: Int) {
    Chat(R.string.nav_chat, R.string.empty_chat, R.drawable.ic_chat),
    Files(R.string.nav_files, R.string.empty_files, R.drawable.ic_files),
    Changes(R.string.nav_changes, R.string.empty_changes, R.drawable.ic_changes),
    Terminal(R.string.nav_terminal, R.string.empty_terminal, R.drawable.ic_terminal),
}
