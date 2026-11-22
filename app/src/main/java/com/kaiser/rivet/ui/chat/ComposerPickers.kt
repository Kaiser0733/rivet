package com.kaiser.rivet.ui.chat

import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.kaiser.rivet.chat.SharedFileCopy
import java.io.File

internal enum class ComposerPicker(val label: String) {
    Camera("Camera"), Photos("Photos"), Files("Files"),
}

@Composable
internal fun rememberComposerPickers(
    sessionId: String?,
    onPick: (String?, Uri, Boolean, File?) -> Unit,
    onUnavailable: () -> Unit,
): (ComposerPicker) -> Unit {
    val context = LocalContext.current
    var targetSession by rememberSaveable { mutableStateOf<String?>(null) }
    var cameraPath by rememberSaveable { mutableStateOf<String?>(null) }
    fun cameraUri(file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.shared-files", file)

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val file = cameraPath?.let(::File)
        cameraPath = null
        if (file != null) {
            if (saved && file.length() > 0) onPick(targetSession, cameraUri(file), true, file)
            else file.delete()
        }
    }
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) onPick(targetSession, uri, true, null)
    }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onPick(targetSession, uri, false, null)
    }
    return { picker ->
        targetSession = sessionId
        try {
            when (picker) {
                ComposerPicker.Camera -> {
                    val folder = SharedFileCopy.inbox(context).apply { mkdirs() }
                    val file = File.createTempFile("Photo-", ".jpg", folder)
                    cameraPath = file.path
                    camera.launch(cameraUri(file))
                }
                ComposerPicker.Photos -> photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                ComposerPicker.Files -> files.launch(arrayOf("*/*"))
            }
        } catch (_: ActivityNotFoundException) {
            cameraPath?.let { File(it).delete() }
            cameraPath = null
            onUnavailable()
        } catch (_: java.io.IOException) {
            onUnavailable()
        }
    }
}
