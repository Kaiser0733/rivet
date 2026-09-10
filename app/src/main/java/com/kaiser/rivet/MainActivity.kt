package com.kaiser.rivet

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.kaiser.rivet.ui.RivetApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // AGP 8.x has no BuildConfig version field by default; the package
        // manager is the source of truth for the installed version.
        val versionName = packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        setContent { RivetApp(versionName) }
    }
}
