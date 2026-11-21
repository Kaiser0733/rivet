package com.kaiser.rivet.ui.chat

/** What the person did with an approval card. */
internal enum class ApprovalEvent { Allow, Deny, Dismiss }

/** What the gate should do about it. */
internal enum class ApprovalResponse { Allow, Deny, KeepWaiting }

/**
 * Only the two buttons answer for the person. A scrim tap or Back on a card that
 * was covered by a sheet — or never drawn — must not resolve it as a denial, which
 * is how a pending approval could disappear without ever being seen.
 */
internal fun approvalResponse(event: ApprovalEvent): ApprovalResponse = when (event) {
    ApprovalEvent.Allow -> ApprovalResponse.Allow
    ApprovalEvent.Deny -> ApprovalResponse.Deny
    ApprovalEvent.Dismiss -> ApprovalResponse.KeepWaiting
}
