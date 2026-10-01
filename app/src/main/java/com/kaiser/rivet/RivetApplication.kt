package com.kaiser.rivet

import android.app.Application
import com.kaiser.rivet.runtime.ManagedProcesses

class RivetApplication : Application() {
    val managedProcesses by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { ManagedProcesses() }
}
