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
    onPick: (String?, List<Uri>, Boolean, File?) -> Unit,
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
            if (saved && file.length() > 0) onPick(targetSession, listOf(cameraUri(file)), true, file)
            else file.delete()
        }
    }
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) onPick(targetSession, uris, true, null)
    }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) onPick(targetSession, uris, false, null)
    }
    return { picker ->
        targetSession = sessionId
        try {
            when (picker) {
                ComposerPicker.Camera -> {
                    val folder = File(SharedFileCopy.inbox(context), "camera").apply { mkdirs() }
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
