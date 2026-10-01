package com.kaiser.rivet.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovalPolicyTest {
    private fun tool(
        name: String,
        effect: AgentToolEffect,
        risk: BasicAutonomyRisk = BasicAutonomyRisk.Elevated,
        escalation: Boolean = false,
        blocked: String? = null,
    ): PreparedAgentTool {
        val call = AgentToolCall("id", name, "{}")
        val approval = AgentApprovalRequest(call, "Confirm", "Details")
        return PreparedAgentTool(call, approval, effect = effect, basicAutonomyRisk = risk,
            modelRequestsApproval = escalation, blockedReason = blocked) { AgentToolResult("id", name, "{}") }
    }

    @Test fun askRequiresConfirmationForEveryEffectButReadOnly() {
        assertEquals(ApprovalDecision.AutoAuthorize,
            ApprovalPolicy.decide(AutonomyMode.Ask, tool("read_file", AgentToolEffect.ReadOnly)))
        listOf(AgentToolEffect.WorkspaceMutation, AgentToolEffect.DestructiveWorkspaceMutation,
            AgentToolEffect.ForegroundCommand, AgentToolEffect.Download,
            AgentToolEffect.PersistentProcess, AgentToolEffect.Preview).forEach { effect ->
            assertEquals(effect.name, ApprovalDecision.AskUser,
                ApprovalPolicy.decide(AutonomyMode.Ask, tool("action", effect)))
        }
    }

    @Test fun basicYoloOnlyAutoAuthorizesAppClassifiedRoutineWork() {
        assertEquals(ApprovalDecision.AutoAuthorize, ApprovalPolicy.decide(AutonomyMode.BasicYolo,
            tool("write_file", AgentToolEffect.WorkspaceMutation, BasicAutonomyRisk.Routine)))
        assertEquals(ApprovalDecision.AskUser, ApprovalPolicy.decide(AutonomyMode.BasicYolo,
            tool("delete_path", AgentToolEffect.DestructiveWorkspaceMutation)))
        assertEquals(ApprovalDecision.AskUser, ApprovalPolicy.decide(AutonomyMode.BasicYolo,
            tool("download_file", AgentToolEffect.Download)))
        assertEquals(BasicAutonomyRisk.Routine, ApprovalPolicy.basicCommandRisk("pwd"))
        assertEquals(BasicAutonomyRisk.Routine, ApprovalPolicy.basicCommandRisk("ls -la"))
        assertEquals(BasicAutonomyRisk.Elevated, ApprovalPolicy.basicCommandRisk("./gradlew test"))
        assertEquals(BasicAutonomyRisk.Elevated,
            ApprovalPolicy.basicCommandRisk("ls; touch marker"))
    }

    @Test fun modelCanEscalateOnlyByRequestingConfirmation() {
        assertEquals(ApprovalDecision.AskUser, ApprovalPolicy.decide(AutonomyMode.BasicYolo,
            tool("write_file", AgentToolEffect.WorkspaceMutation, BasicAutonomyRisk.Routine, escalation = true)))
        assertEquals(ApprovalDecision.AskUser, ApprovalPolicy.decide(AutonomyMode.Ask,
            tool("write_file", AgentToolEffect.WorkspaceMutation, BasicAutonomyRisk.Routine)))
        assertEquals(ApprovalDecision.AutoAuthorize, ApprovalPolicy.decide(AutonomyMode.Yolo,
            tool("write_file", AgentToolEffect.WorkspaceMutation, BasicAutonomyRisk.Routine, escalation = true)))
    }

    @Test fun yoloRemovesPromptsButCannotRemoveHardBlocks() {
        listOf(AgentToolEffect.WorkspaceMutation, AgentToolEffect.DestructiveWorkspaceMutation,
            AgentToolEffect.ForegroundCommand, AgentToolEffect.Download,
            AgentToolEffect.PersistentProcess, AgentToolEffect.Preview).forEach { effect ->
            assertEquals(effect.name, ApprovalDecision.AutoAuthorize,
                ApprovalPolicy.decide(AutonomyMode.Yolo, tool("action", effect)))
        }
        assertEquals(ApprovalDecision.Blocked("unsupported_system_management"),
            ApprovalPolicy.decide(AutonomyMode.Yolo,
                tool("run_command", AgentToolEffect.ForegroundCommand,
                    blocked = "unsupported_system_management")))
    }

    @Test fun obviousSystemManagementCommandsAreBlockedInEveryMode() {
        listOf("pm install example.apk", "  sudo apt update", "cmd package install foo.apk",
            "su", "apt update", "pkg install git", "am start -n example/.Main")
            .forEach { command -> assertTrue(command, ApprovalPolicy.blockedCommandReason(command) != null) }
        assertEquals(null, ApprovalPolicy.blockedCommandReason("./gradlew test"))
    }

    @Test fun missingApprovalDescriptionFailsClosed() {
        val call = AgentToolCall("id", "write_file", "{}")
        val prepared = PreparedAgentTool(call, null, effect = AgentToolEffect.WorkspaceMutation) { error("must not run") }
        assertEquals(ApprovalDecision.Blocked("approval_unavailable"),
            ApprovalPolicy.decide(AutonomyMode.Ask, prepared))
    }
}
