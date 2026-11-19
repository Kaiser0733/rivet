package com.kaiser.rivet

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import com.kaiser.rivet.chat.ChatViewModel
import com.kaiser.rivet.chat.SharedContent
import com.kaiser.rivet.ui.RivetApp
import com.kaiser.rivet.local.ModelsViewModel
import com.kaiser.rivet.ui.provider.ProvidersViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

class MainActivity : ComponentActivity() {
    private lateinit var chatViewModel: ChatViewModel
    private val processNavigationRequest = MutableStateFlow(0)
    private val phoneCapture get() = (application as RivetApplication).phoneCapture
    private val capturePermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        phoneCapture.consent(it.resultCode, it.data)
    }
    private val openCapturePermission: (Intent) -> Boolean = { intent ->
        lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
            runCatching { capturePermission.launch(intent) }.isSuccess
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        phoneCapture.consentLauncher = openCapturePermission
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 43)
        }
        // Draw edge to edge so the IME inset reaches Compose; adjustResize then makes
        // imePadding lift the composer instead of the window panning the send button away.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // The system redelivers the original launch intent after a recreation (the extra removed
        // by consumeProcessRequest only reaches this instance's Intent, not the activity record),
        // so a rotation would replay "open Processes" over the screen the user navigated back to.
        // Restoring is not launching: only a fresh launch handles the request.
        chatViewModel = ViewModelProvider(this)[ChatViewModel::class.java]
        if (savedInstanceState == null) {
            consumeProcessRequest(intent)
            consumeSharedContent(this.intent)
        }
        // AGP 8.x has no BuildConfig version field by default; the package
        // manager is the source of truth for the installed version.
        val versionName = packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        val providersViewModel = ViewModelProvider(this)[ProvidersViewModel::class.java]
        val modelsViewModel = ViewModelProvider(this)[ModelsViewModel::class.java]
        setContent {
            val request by processNavigationRequest.collectAsState()
            RivetApp(versionName, chatViewModel, providersViewModel, modelsViewModel, request)
        }
    }

    override fun onStop() {
        phoneCapture.rivetVisible = false
        super.onStop()
        if (::chatViewModel.isInitialized) chatViewModel.browser.onAppBackground()
    }

    override fun onStart() {
        super.onStart()
        phoneCapture.rivetVisible = true
        if (::chatViewModel.isInitialized) chatViewModel.browser.onAppForeground()
    }

    override fun onResume() {
        super.onResume()
        phoneCapture.rivetResumed = true
    }

    override fun onPause() {
        phoneCapture.rivetResumed = false
        super.onPause()
    }

    override fun onDestroy() {
        if (phoneCapture.consentLauncher === openCapturePermission) phoneCapture.consentLauncher = null
        super.onDestroy()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeProcessRequest(intent)
        consumeSharedContent(this.intent)
    }

    @Suppress("DEPRECATION")
    private fun consumeSharedContent(intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_VIEW) return
        val stream = runCatching { intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) }.getOrNull()
        val uri = if (action == Intent.ACTION_VIEW) intent.data else stream ?: intent.clipData?.let {
            if (it.itemCount > 0) it.getItemAt(0).uri else null
        }
        val text = if (action == Intent.ACTION_SEND) intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString() else null
        val content = SharedContent.classify(action, intent.type, text, uri?.toString())
        setIntent(Intent(intent).apply {
            removeExtra(Intent.EXTRA_STREAM)
            removeExtra(Intent.EXTRA_TEXT)
            clipData = null
            data = null
            setAction(null)
        })
        content?.let(chatViewModel::receiveSharedContent)
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
