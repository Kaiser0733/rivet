package com.kaiser.rivet.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.kaiser.rivet.R
import com.kaiser.rivet.ui.dismissOnEscape

internal class AttachmentSheetPosition(private val composerInset: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val start = when (layoutDirection) {
            LayoutDirection.Ltr -> anchorBounds.left - composerInset
            LayoutDirection.Rtl -> anchorBounds.right + composerInset - popupContentSize.width
        }
        return IntOffset(
            start.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
            (anchorBounds.top - popupContentSize.height).coerceIn(
                0, (windowSize.height - popupContentSize.height).coerceAtLeast(0),
            ),
        )
    }
}

@Composable
internal fun AttachmentSheet(composerInset: Dp, onDismiss: () -> Unit, onPick: (ComposerPicker) -> Unit) {
    val inset = with(LocalDensity.current) { composerInset.roundToPx() }
    Popup(
        popupPositionProvider = remember(inset) { AttachmentSheetPosition(inset) },
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Surface(
            modifier = Modifier.width(264.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 6.dp,
        ) {
            Column(
                Modifier.dismissOnEscape(dismiss = onDismiss).verticalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            ) {
                ComposerPicker.entries.forEach { picker ->
                    val icon = when (picker) {
                        ComposerPicker.Camera -> R.drawable.ic_camera
                        ComposerPicker.Photos -> R.drawable.ic_photo
                        ComposerPicker.Files -> R.drawable.ic_attachment
                    }
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 60.dp)
                            .clip(MaterialTheme.shapes.medium)
                            .clickable(role = Role.Button) {
                                onDismiss()
                                onPick(picker)
                            }.padding(horizontal = 8.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(44.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(22.dp))
                        }
                        Text(picker.label, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}
