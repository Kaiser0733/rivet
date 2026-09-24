package com.kaiser.rivet

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import com.kaiser.rivet.chat.ChatViewModel
import com.kaiser.rivet.ui.RivetApp
import com.kaiser.rivet.ui.provider.ProvidersViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // AGP 8.x has no BuildConfig version field by default; the package
        // manager is the source of truth for the installed version.
        val versionName = packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        // The activity's default factory supplies the Application to both
        // AndroidViewModels; no custom factory needed.
        val chatViewModel = ViewModelProvider(this)[ChatViewModel::class.java]
        val providersViewModel = ViewModelProvider(this)[ProvidersViewModel::class.java]
        setContent { RivetApp(versionName, chatViewModel, providersViewModel) }
    }
}
