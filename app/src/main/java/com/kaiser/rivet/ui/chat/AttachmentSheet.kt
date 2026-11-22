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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.kaiser.rivet.R
import com.kaiser.rivet.ui.dismissOnEscape

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AttachmentSheet(onDismiss: () -> Unit, onPick: (ComposerPicker) -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetMaxWidth = 280.dp,
        containerColor = Color.Transparent,
        dragHandle = null,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
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
