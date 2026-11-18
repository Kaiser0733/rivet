package com.kaiser.rivet.agent

sealed interface ApprovalDecision {
    data object AskUser : ApprovalDecision
    data object AutoAuthorize : ApprovalDecision
    data class Blocked(val reason: String) : ApprovalDecision
}

/** Human confirmation is a product policy; tool effects continue to govern integrity checks. */
object ApprovalPolicy {
    fun decide(mode: AutonomyMode, tool: PreparedAgentTool): ApprovalDecision {
        tool.blockedReason?.let { return ApprovalDecision.Blocked(it) }
        if (tool.effect == AgentToolEffect.ReadOnly) return ApprovalDecision.AutoAuthorize
        val ask = when (mode) {
            AutonomyMode.Ask -> true
            AutonomyMode.BasicYolo -> tool.modelRequestsApproval ||
                tool.basicAutonomyRisk != BasicAutonomyRisk.Routine
            AutonomyMode.Yolo -> false
        }
        if (!ask) return ApprovalDecision.AutoAuthorize
        return if (tool.approval == null) {
            if (tool.effect == AgentToolEffect.Delegation) ApprovalDecision.AutoAuthorize
            else ApprovalDecision.Blocked("approval_unavailable")
        } else ApprovalDecision.AskUser
    }

    /** Obvious Android/app management attempts stay outside Rivet's command capability. */
    fun blockedCommandReason(command: String): String? {
        val normalized = command.trimStart().lowercase().replace('\\', '/')
        val pattern = Regex("(?:^|[\\r\\n;&|()]\\s*)(?:su|sudo|doas|run-as|adb|pm|pkg|apt(?:-get)?|cmd\\s+package|am\\s+(?:start|force-stop))(?:\\s|$)")
        return if (pattern.containsMatchIn(normalized)) "unsupported_system_management" else null
    }

    /** Basic mode auto-runs only uncomplicated inspection of the current directory. */
    fun basicCommandRisk(command: String): BasicAutonomyRisk {
        val value = command.trim()
        if (value.any { it.isISOControl() || it in ";&|<>`$()\\\\" } || '\n' in value) {
            return BasicAutonomyRisk.Elevated
        }
        val parts = value.split(Regex("\\s+")).filter(String::isNotEmpty)
        val command = parts.firstOrNull()
        return when {
            command == "pwd" -> BasicAutonomyRisk.Routine
            command == "ls" && parts.drop(1).all { it.matches(Regex("-[alhA]+")) } ->
                BasicAutonomyRisk.Routine
            command != null && command in READ_ONLY_INSPECTION &&
                !(command == "tail" && follows(parts)) -> BasicAutonomyRisk.Routine
            else -> BasicAutonomyRisk.Elevated
        }
    }

    private fun follows(parts: List<String>): Boolean = parts.drop(1).any {
        it == "--retry" || it.startsWith("--follow") ||
            (it.startsWith("-") && !it.startsWith("--") &&
                it.drop(1).any { flag -> flag == 'f' || flag == 'F' })
    }

    private val READ_ONLY_INSPECTION = setOf("wc", "head", "tail", "cat", "grep")
}
