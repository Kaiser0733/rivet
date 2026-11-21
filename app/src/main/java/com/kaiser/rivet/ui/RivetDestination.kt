package com.kaiser.rivet.ui

internal enum class RivetDestination {
    Chat, Projects, Settings, Tools, Terminal, Workers, Models, SkillInstall;

    fun backDestination(returnTo: RivetDestination = Chat): RivetDestination = when (this) {
        Terminal, Workers -> Tools
        Models, SkillInstall -> returnTo
        Chat, Projects, Settings, Tools -> Chat
    }
}
