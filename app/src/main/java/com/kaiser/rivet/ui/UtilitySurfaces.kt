package com.kaiser.rivet.ui

import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.hoverable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.widthIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.drawBehind
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.ui.unit.IntSize
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.kaiser.rivet.R

@Composable
internal fun UtilityGlyph(icon: Int) {
    Box(
        Modifier.size(36.dp).clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable
internal fun UtilityRow(
    icon: Int,
    title: String,
    value: String = "",
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    divider: Boolean = true,
    trailingIcon: Int = R.drawable.ic_chevron_right,
    titleMaxLines: Int = Int.MAX_VALUE,
    onClick: () -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .utilityClick(enabled = enabled, onClick = onClick)
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            UtilityGlyph(icon)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall,
                    maxLines = titleMaxLines, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.55f))
                if (value.isNotBlank()) Text(value, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(painterResource(trailingIcon), null, Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (divider) HorizontalDivider(Modifier.padding(start = 52.dp),
            color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
internal fun UtilityLead(
    icon: Int,
    title: String,
    detail: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    Surface(
        modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                UtilityGlyph(icon)
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleLarge)
                    if (detail != null) Text(detail, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            content()
        }
    }
}

@Composable
internal fun UtilityFold(
    icon: Int,
    title: String,
    value: String = "",
    modifier: Modifier = Modifier,
    titleMaxLines: Int = Int.MAX_VALUE,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        UtilityRow(icon, title, value, divider = false, titleMaxLines = titleMaxLines,
            modifier = Modifier.semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" },
            trailingIcon = if (expanded) R.drawable.ic_chevron_down else R.drawable.ic_chevron_right,
            onClick = { expanded = !expanded })
        DisclosureBody(expanded) {
            Column(Modifier.fillMaxWidth().padding(start = 52.dp, end = 8.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
        }
        HorizontalDivider(Modifier.padding(start = 52.dp), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
internal fun DisclosureBody(expanded: Boolean, content: @Composable () -> Unit) {
    val spec: FiniteAnimationSpec<IntSize> = rivetMotionSpec(RivetMotion.DISCLOSURE_MS)
    AnimatedVisibility(
        visible = expanded,
        enter = expandVertically(animationSpec = spec, expandFrom = Alignment.Top),
        exit = shrinkVertically(animationSpec = spec, shrinkTowards = Alignment.Top),
    ) { content() }
}

@Composable
internal fun Modifier.utilityClick(enabled: Boolean = true, onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val focused by source.collectIsFocusedAsState()
    val hovered by source.collectIsHoveredAsState()
    return pressLayer(pressed, hovered, focused).hoverable(source, enabled).clickable(
        enabled = enabled, role = Role.Button, onClick = onClick, interactionSource = source, indication = null,
    )
}

@Composable
internal fun Modifier.pressLayer(pressed: Boolean, hovered: Boolean = false, focused: Boolean = false): Modifier {
    val ink = MaterialTheme.colorScheme.primary
    val target = when {
        pressed -> RivetMotion.pressAlpha
        hovered || focused -> RivetMotion.hoverAlpha
        else -> 0f
    }
    val amount by animateFloatAsState(target, animationSpec = rivetMotionSpec(RivetMotion.FOCUS_MS), label = "Control feedback")
    return drawBehind {
        if (amount > 0f) drawRect(ink.copy(alpha = amount))
        if (focused && size.width > 2.dp.toPx() && size.height > 2.dp.toPx()) {
            val inset = 1.dp.toPx()
            drawRect(ink, topLeft = Offset(inset, inset), size = Size(size.width - 2 * inset, size.height - 2 * inset),
                style = Stroke(2 * inset))
        }
    }
}

@Composable
internal fun UtilityColumns(
    modifier: Modifier = Modifier,
    stackedGap: Dp = RivetLayout.sectionGap,
    leading: @Composable ColumnScope.() -> Unit,
    trailing: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        if (maxWidth >= RivetLayout.columnBreakpoint) {
            Row(horizontalArrangement = Arrangement.spacedBy(RivetLayout.sectionGap)) {
                Column(Modifier.weight(1f), content = leading)
                Column(Modifier.weight(1f), content = trailing)
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(stackedGap)) {
                leading()
                trailing()
            }
        }
    }
}
