package com.kaiser.rivet.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProduceStateScope
import androidx.compose.runtime.withFrameNanos
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
    onImageBounds: suspend (Size) -> Unit = {},
): FilePreviewResult = withContext(Dispatchers.IO) {
    try {
        val file = File(path.removePrefix("file://")).canonicalFile
        val allowed = file.path.startsWith(context.filesDir.canonicalPath + "/") ||
            file.path.startsWith(context.cacheDir.canonicalPath + "/")
        if (!allowed || !file.isFile) return@withContext FilePreviewResult.Missing

        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) {
            return@withContext FilePreviewResult.Unavailable(file.length(), "This image can't be previewed.")
        }
        onImageBounds(Size(options.outWidth, options.outHeight))
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

internal sealed interface ChatImageState {
    val bounds: Size?

    data object Pending : ChatImageState {
        override val bounds: Size? = null
    }

    data class Loading(override val bounds: Size) : ChatImageState
    data class Ready(override val bounds: Size, val bitmap: Bitmap) : ChatImageState
    data class Missing(override val bounds: Size?) : ChatImageState
}

internal suspend fun ProduceStateScope<ChatImageState>.loadChatImage(
    load: suspend (suspend (Size) -> Unit) -> FilePreviewResult,
) {
    val result = load { bounds ->
        withContext(Dispatchers.Main.immediate) {
            value = ChatImageState.Loading(bounds)
            withFrameNanos { }
        }
    }
    value = if (result is FilePreviewResult.Image) {
        ChatImageState.Ready(value.bounds ?: Size(result.bitmap.width, result.bitmap.height), result.bitmap)
    } else {
        ChatImageState.Missing(value.bounds)
    }
}

@Composable
internal fun ReservedChatImage(
    state: ChatImageState,
    description: String,
    open: (() -> Unit)? = null,
    inline: Boolean = true,
    unavailable: @Composable () -> Unit,
) {
    val bounds = state.bounds
    if (bounds == null) {
        if (state is ChatImageState.Missing) unavailable()
        return
    }

    val screenHeight = LocalConfiguration.current.screenHeightDp.toFloat()
    val imageWidth = with(LocalDensity.current) { bounds.width.toDp().value }
    val imageHeight = with(LocalDensity.current) { bounds.height.toDp().value }
    val shape = if (inline) MaterialTheme.shapes.small else RoundedCornerShape(6.dp)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val (width, height) = if (inline) {
            inlineMessageImageSize(imageWidth, imageHeight, maxWidth.value, screenHeight)
        } else {
            maxWidth.value to maxWidth.value * imageHeight / imageWidth
        }
        Box(Modifier.size(width.dp, height.dp).clip(shape)) {
            when (state) {
                is ChatImageState.Ready -> Image(
                    bitmap = state.bitmap.asImageBitmap(),
                    contentDescription = description,
                    modifier = Modifier.matchParentSize()
                        .then(if (open != null) Modifier.clickable(role = Role.Button,
                            onClickLabel = "Open image preview", onClick = open) else Modifier),
                    contentScale = ContentScale.Fit,
                )
                is ChatImageState.Loading -> Box(
                    Modifier.matchParentSize().background(MaterialTheme.colorScheme.surfaceContainerLow),
                )
                is ChatImageState.Missing -> unavailable()
                ChatImageState.Pending -> Unit
            }
        }
    }
}
