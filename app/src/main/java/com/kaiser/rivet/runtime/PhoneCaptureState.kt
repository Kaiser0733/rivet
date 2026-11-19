package com.kaiser.rivet.runtime

internal enum class PhoneCaptureEvent { Request, Consent, Deny, Capture, Finish, Cancel }

internal enum class PhoneCaptureState {
    Idle, Armed, Ready, Capturing, Done, Denied, Cancelled;

    val canStartService: Boolean get() = this == Ready

    fun transition(event: PhoneCaptureEvent): PhoneCaptureState? = when (event) {
        PhoneCaptureEvent.Request -> if (this == Idle) Armed else null
        PhoneCaptureEvent.Consent -> if (this == Armed) Ready else null
        PhoneCaptureEvent.Deny -> if (this == Armed) Denied else null
        PhoneCaptureEvent.Capture -> if (this == Ready) Capturing else null
        PhoneCaptureEvent.Finish -> if (this == Capturing) Done else null
        PhoneCaptureEvent.Cancel -> if (this in setOf(Armed, Ready, Capturing)) Cancelled else null
    }
}

internal data class PhoneCaptureResult(
    val error: String? = null,
    val path: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val rivetVisible: Boolean = false,
)
