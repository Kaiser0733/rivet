package com.kaiser.rivet.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.kaiser.rivet.workspace.FilePreviewResult
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal val LocalOpenChatImage = staticCompositionLocalOf<((String) -> Unit)?> { null }

internal fun isAppChatImagePath(path: String): Boolean =
    path.startsWith('/') || path.startsWith("file://")

internal suspend fun loadAppChatImage(
    context: Context,
    path: String,
    maxEdge: Int = 2560,
): FilePreviewResult = withContext(Dispatchers.IO) {
    try {
        val file = File(path.removePrefix("file://")).canonicalFile
        val allowed = file.path.startsWith(context.filesDir.canonicalPath + "/") ||
            file.path.startsWith(context.cacheDir.canonicalPath + "/")
        if (!allowed || !file.isFile) return@withContext FilePreviewResult.Missing

        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, options)
        val edge = maxOf(options.outWidth, options.outHeight)
        var sample = 1
        while (edge / sample > maxEdge) sample *= 2
        options.inSampleSize = sample
        options.inJustDecodeBounds = false
        val bitmap = BitmapFactory.decodeFile(file.path, options)
        if (bitmap == null) FilePreviewResult.Unavailable(file.length(), "This image can't be previewed.")
        else FilePreviewResult.Image(bitmap)
    } catch (_: IOException) {
        FilePreviewResult.Missing
    }
}

@Composable
internal fun InlineChatImage(bitmap: Bitmap, description: String, open: (() -> Unit)?) {
    val screenHeight = LocalConfiguration.current.screenHeightDp.toFloat()
    val imageWidth = with(LocalDensity.current) { bitmap.width.toDp().value }
    val imageHeight = with(LocalDensity.current) { bitmap.height.toDp().value }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val (width, height) = inlineMessageImageSize(imageWidth, imageHeight, maxWidth.value, screenHeight)
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = description,
            modifier = Modifier.size(width.dp, height.dp).clip(MaterialTheme.shapes.small)
                .then(if (open != null) Modifier.clickable(role = Role.Button,
                    onClickLabel = "Open image preview", onClick = open) else Modifier),
            contentScale = ContentScale.Fit,
        )
    }
}
