package com.kaiser.rivet.ui

enum class RivetDestination(val isPrimary: Boolean) {
    Chat(true),
    History(false),
    Settings(true),
    Processes(false),
}
