package com.kaiser.rivet.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.kaiser.rivet.R
import com.kaiser.rivet.ui.RivetMotion
import com.kaiser.rivet.ui.motionDisabled
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay

@Composable
internal fun CompactingRow() {
    val static = motionDisabled()
    var dots by remember { mutableIntStateOf(1) }
    LaunchedEffect(static) {
        if (static) return@LaunchedEffect
        val scale = currentCoroutineContext()[MotionDurationScale]
        while (true) {
            val factor = scale?.scaleFactor ?: 1f
            if (factor == 0f) break
            delay((RivetMotion.COMPACT_DOT_MS * factor).toLong().coerceAtLeast(1L))
            dots = dots % 3 + 1
        }
    }
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(R.drawable.ic_memory), null, Modifier.size(18.dp), tint = ink)
        Row {
            Text("Compacting", style = MaterialTheme.typography.bodySmall, color = ink)
            Text(".".repeat(if (static) 3 else dots), Modifier.width(20.dp).clearAndSetSemantics {},
                style = MaterialTheme.typography.bodySmall, color = ink)
        }
    }
}
