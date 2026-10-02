package com.kaiser.rivet

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import com.kaiser.rivet.chat.ChatViewModel
import com.kaiser.rivet.ui.RivetApp
import com.kaiser.rivet.ui.provider.ProvidersViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

class MainActivity : ComponentActivity() {
    private val processNavigationRequest = MutableStateFlow(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        consumeProcessRequest(intent)
        // AGP 8.x has no BuildConfig version field by default; the package
        // manager is the source of truth for the installed version.
        val versionName = packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        val chatViewModel = ViewModelProvider(this)[ChatViewModel::class.java]
        val providersViewModel = ViewModelProvider(this)[ProvidersViewModel::class.java]
        setContent {
            val request by processNavigationRequest.collectAsState()
            RivetApp(versionName, chatViewModel, providersViewModel, request)
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeProcessRequest(intent)
    }

    private fun consumeProcessRequest(intent: android.content.Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_PROCESSES, false) == true) {
            processNavigationRequest.value += 1
            val clearedIntent = android.content.Intent(intent).apply { removeExtra(EXTRA_OPEN_PROCESSES) }
            setIntent(clearedIntent)
        }
    }

    companion object {
        const val EXTRA_OPEN_PROCESSES = "com.kaiser.rivet.OPEN_PROCESSES"
    }
}
