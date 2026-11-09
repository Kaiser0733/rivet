package com.kaiser.rivet

import android.app.Application
import com.kaiser.rivet.local.LocalRuntime
import com.kaiser.rivet.runtime.ManagedProcesses

class RivetApplication : Application() {
    val managedProcesses by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { ManagedProcesses() }

    override fun onCreate() {
        super.onCreate()
        // The provider factory needs the same application-owned model slot the
        // Models screen uses. Initialising here also restores download journals
        // before the first screen can ask for them.
        LocalRuntime.initialize(this)
    }
}
