package com.kaiser.rivet.browser

enum class BrowserDriving { Agent, User }

/**
 * Driving is a run property, not a browser property. A manual browser open has no
 * banner and no chat card; only an agent turn that actually started the visual
 * browser can hand control back and forth.
 */
object BrowserDrivingPolicy {
    /** The single derivation of visual activity, shared by both UI gates. */
    fun visualActive(agentRunActive: Boolean, agentRendererUse: Boolean,
                     rendererAlive: Boolean): Boolean =
        agentRunActive && agentRendererUse && rendererAlive

    fun bannerVisible(agentRunActive: Boolean, agentVisualActive: Boolean,
                      driving: BrowserDriving): Boolean =
        agentRunActive && agentVisualActive && driving == BrowserDriving.User

    fun cardVisible(agentRunActive: Boolean, agentVisualActive: Boolean,
                    watchVisible: Boolean, screenVisible: Boolean): Boolean =
        agentRunActive && agentVisualActive && watchVisible && !screenVisible

    fun mayAgentAct(agentRunActive: Boolean, agentVisualActive: Boolean,
                    driving: BrowserDriving): Boolean =
        !agentRunActive || !agentVisualActive || driving == BrowserDriving.Agent

    const val RELEASE_CONTROL_AFTER_MS = 30_000L
}
