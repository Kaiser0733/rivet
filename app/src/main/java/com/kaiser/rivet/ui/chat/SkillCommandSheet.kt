package com.kaiser.rivet.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kaiser.rivet.agent.Skill

/**
 * The token the composer is typing after a leading "/", or null when the text does not
 * start with "/" or the first token is already finished (contains whitespace).
 */
internal fun skillCommandQuery(text: String): String? {
    if (!text.startsWith("/")) return null
    val token = text.substring(1)
    if (token.any { it.isWhitespace() }) return null
    return token
}

/** The "/" list: user-invocable skills whose name starts with the typed prefix. */
internal fun matchingSkills(skills: List<Skill>, query: String): List<Skill> =
    skills.filter { it.userInvocable && it.name.startsWith(query, ignoreCase = true) }

/**
 * Filtered skill picker shown above the composer while a "/" token is being typed.
 * Picking a row only completes the `/name ` text; sending is what loads the skill.
 */
@Composable
internal fun SkillCommandSheet(skills: List<Skill>, onPick: (Skill) -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.fillMaxWidth()
            .shadow(8.dp, shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(vertical = 4.dp),
    ) {
        LazyColumn(Modifier.heightIn(max = 260.dp)) {
            items(skills, key = { it.name }) { skill ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .clickable { onPick(skill) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(skill.name, style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(displaySafeText(skill.description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
