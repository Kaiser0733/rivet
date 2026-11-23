package com.kaiser.rivet.ui.chat

import android.text.format.Formatter
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kaiser.rivet.R
import com.kaiser.rivet.chat.ComposerAttachment
import com.kaiser.rivet.chat.SharedFileCopy
import com.kaiser.rivet.workspace.FilePreviewResult
import java.io.File
import java.util.Locale

@Composable
internal fun ComposerAttachmentStrip(
    attachments: List<ComposerAttachment>,
    sessionId: String?,
    canRemove: Boolean,
    onRemove: (String) -> Unit,
) {
    val context = LocalContext.current
    LazyRow(
        Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(attachments, key = { it.name }) { item ->
            Box(
                Modifier.width(if (item.image) 76.dp else 178.dp).height(76.dp)
                    .clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                if (item.image) {
                    val file = File(SharedFileCopy.sessionInbox(context, sessionId), item.name)
                    val preview by produceState<FilePreviewResult?>(null, file) {
                        value = loadAppChatImage(context, file.path, maxEdge = 256)
                    }
                    val image = preview as? FilePreviewResult.Image
                    if (image != null) {
                        Image(image.bitmap.asImageBitmap(), item.name, Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop)
                    } else {
                        Icon(painterResource(R.drawable.ic_photo), item.name, Modifier.align(Alignment.Center))
                    }
                } else {
                    Row(Modifier.fillMaxSize().padding(start = 10.dp, end = 30.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(R.drawable.ic_files), null, Modifier.size(24.dp))
                        Column {
                            Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelLarge)
                            val type = item.name.substringAfterLast('.', "").uppercase(Locale.ROOT).take(8)
                            val size = Formatter.formatShortFileSize(context, item.bytes)
                            Text(listOf(type, size).filter(String::isNotBlank).joinToString(" "),
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                IconButton(
                    onClick = { onRemove(item.name) }, enabled = canRemove,
                    modifier = Modifier.align(Alignment.TopEnd).size(36.dp),
                ) {
                    Box(Modifier.size(20.dp).background(MaterialTheme.colorScheme.surface, CircleShape),
                        contentAlignment = Alignment.Center) {
                        Icon(painterResource(R.drawable.ic_close), "Remove ${item.name}", Modifier.size(14.dp))
                    }
                }
            }
        }
    }
}
