package com.kaiser.rivet.ui.provider

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kaiser.rivet.agent.GitHubSource
import com.kaiser.rivet.agent.SkillInstallFailure
import com.kaiser.rivet.agent.SkillPayload
import com.kaiser.rivet.agent.copySharedZip
import com.kaiser.rivet.agent.fetchGitHubSkill
import com.kaiser.rivet.agent.githubCandidates
import com.kaiser.rivet.agent.globalSkillsRoot
import com.kaiser.rivet.agent.installConflict
import com.kaiser.rivet.agent.installSkill
import com.kaiser.rivet.agent.isBundledScript
import com.kaiser.rivet.agent.parseGitHubLink
import com.kaiser.rivet.agent.readZipSkill
import com.kaiser.rivet.agent.sharedZipName
import com.kaiser.rivet.agent.zipSkillCandidates
import com.kaiser.rivet.ui.chat.displaySafeText
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private sealed interface InstallStep {
    data class Link(val error: String? = null) : InstallStep
    data class Busy(val label: String) : InstallStep
    data class Choose(
        val source: GitHubSource?,
        val zip: File?,
        val zipLabel: String,
        val items: List<Pair<String, String>>,
    ) : InstallStep
    data class Review(val payload: SkillPayload, val replace: Boolean) : InstallStep
    data class Installed(val name: String) : InstallStep
    data class Failed(val message: String) : InstallStep
}

/**
 * One sheet for both install routes: a GitHub link, or a zip shared to Rivet. Nothing is
 * written to the global skills folder until the user confirms on the review step.
 */
@Composable
internal fun SkillInstallDialog(
    initialZip: Uri?,
    onDismiss: () -> Unit,
    onInstalled: suspend () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val skillsRoot = remember(context) { globalSkillsRoot(context.filesDir) }
    val scope = rememberCoroutineScope()
    var link by rememberSaveable { mutableStateOf("") }
    var handledInitialZipUri by rememberSaveable { mutableStateOf<String?>(null) }
    var savedStepKind by rememberSaveable { mutableStateOf<String?>(null) }
    var savedStepDetail by rememberSaveable { mutableStateOf("") }
    var step by remember {
        mutableStateOf<InstallStep>(
            when (savedStepKind) {
                SkillInstallStepPolicy.KIND_INSTALLED -> InstallStep.Installed(savedStepDetail)
                SkillInstallStepPolicy.KIND_FAILED -> InstallStep.Failed(savedStepDetail)
                else -> if (SkillInstallStepPolicy.shouldRunInitialZip(
                        initialZip?.toString(), handledInitialZipUri, savedStepKind)) {
                    InstallStep.Busy("Reading the zip…")
                } else {
                    InstallStep.Link()
                }
            },
        )
    }

    fun markFailed(message: String) {
        savedStepKind = SkillInstallStepPolicy.KIND_FAILED
        savedStepDetail = message
        step = InstallStep.Failed(message)
    }

    fun markInstalled(name: String) {
        savedStepKind = SkillInstallStepPolicy.KIND_INSTALLED
        savedStepDetail = name
        step = InstallStep.Installed(name)
    }

    fun review(payload: SkillPayload) {
        savedStepKind = SkillInstallStepPolicy.KIND_LINK
        step = InstallStep.Review(payload, installConflict(skillsRoot, payload.skill.name))
    }

    fun fail(error: Exception) {
        markFailed(SkillInstallStepPolicy.failureMessage(error))
    }

    suspend fun openZip(uri: Uri) {
        try {
            val zipLabel = sharedZipName(context, uri)
            val zip = copySharedZip(context, uri)
            val roots = zipSkillCandidates(zip)
            when {
                roots.isEmpty() -> markFailed("That zip doesn't contain a SKILL.md.")
                roots.size == 1 -> review(readZipSkill(zip, roots[0], zipLabel))
                else -> {
                    savedStepKind = SkillInstallStepPolicy.KIND_LINK
                    step = InstallStep.Choose(null, zip, zipLabel,
                        roots.map { labelFor(it, zipLabel) to it })
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(e)
        }
        handledInitialZipUri = uri.toString()
    }

    suspend fun submitLink() {
        val source = parseGitHubLink(link)
        if (source == null) {
            step = InstallStep.Link("That doesn't look like a GitHub link.")
            return
        }
        step = InstallStep.Busy("Looking for skills…")
        try {
            val candidates = githubCandidates(source)
            when {
                candidates.isEmpty() ->
                    markFailed("No folder with a SKILL.md was found there.")
                candidates.size == 1 -> review(fetchGitHubSkill(source, candidates[0].path))
                else -> step = InstallStep.Choose(source, null, "", candidates.map {
                    labelFor(it.path, "") to it.path
                })
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(e)
        }
    }

    LaunchedEffect(initialZip) {
        val uriKey = initialZip?.toString()
        if (initialZip != null && SkillInstallStepPolicy.shouldRunInitialZip(
                uriKey, handledInitialZipUri, savedStepKind)) {
            openZip(initialZip)
        }
    }

    Dialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Surface(
                Modifier.fillMaxWidth().widthIn(max = 460.dp).heightIn(max = 560.dp),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(
                    Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
                ) {
                    Text(titleFor(step), style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(12.dp))
                    when (val current = step) {
                        is InstallStep.Link -> LinkStep(
                            link = link,
                            error = current.error,
                            onLinkChange = { link = it },
                            onSubmit = { scope.launch { submitLink() } },
                            onCancel = onDismiss,
                        )
                        is InstallStep.Busy -> {
                            CircularProgressIndicator(Modifier.size(28.dp))
                            Spacer(Modifier.height(12.dp))
                            Text(current.label, style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        is InstallStep.Choose -> ChooseStep(current, onPick = { path ->
                            val source = current.source
                            val zip = current.zip
                            scope.launch {
                                step = InstallStep.Busy("Reading the skill…")
                                try {
                                    val payload = when {
                                        zip != null -> readZipSkill(zip, path, current.zipLabel)
                                        source != null -> fetchGitHubSkill(source, path)
                                        else -> throw SkillInstallFailure("That skill couldn't be read.")
                                    }
                                    review(payload)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    fail(e)
                                }
                            }
                        }, onCancel = onDismiss)
                        is InstallStep.Review -> ReviewStep(
                            current,
                            onCancel = onDismiss,
                            onInstall = {
                                scope.launch {
                                    step = InstallStep.Busy("Installing…")
                                    try {
                                        installSkill(current.payload, skillsRoot)
                                        onInstalled()
                                        markInstalled(current.payload.skill.name)
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        fail(e)
                                    }
                                }
                            },
                        )
                        is InstallStep.Installed -> {
                            Text("Installed ${current.name}. The skill is available in every project.",
                                style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(16.dp))
                            Row(Modifier.fillMaxWidth()) {
                                Spacer(Modifier.weight(1f))
                                Button(onClick = onDismiss) { Text("Done") }
                            }
                        }
                        is InstallStep.Failed -> {
                            Text(displaySafeText(current.message),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.height(16.dp))
                            Row(Modifier.fillMaxWidth()) {
                                Spacer(Modifier.weight(1f))
                                Button(onClick = onDismiss) { Text("Close") }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun titleFor(step: InstallStep): String = when (step) {
    is InstallStep.Link -> "Install a skill"
    is InstallStep.Busy -> "Install a skill"
    is InstallStep.Choose -> "Choose a skill"
    is InstallStep.Review -> "Review ${step.payload.skill.name}"
    is InstallStep.Installed -> "Installed"
    is InstallStep.Failed -> "Couldn't install"
}

private fun labelFor(path: String, zipLabel: String): String {
    val trimmed = path.trim('/')
    return when {
        trimmed.isNotEmpty() -> trimmed
        zipLabel.isNotEmpty() -> "$zipLabel (the zip itself)"
        else -> "The whole repository"
    }
}

@Composable
private fun LinkStep(
    link: String,
    error: String?,
    onLinkChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
) {
    Text("Paste a public GitHub repository or a folder link. Rivet finds any folder with a SKILL.md.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = link,
        onValueChange = onLinkChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("GitHub link") },
        placeholder = { Text("https://github.com/owner/repo") },
        singleLine = true,
    )
    if (error != null) {
        Spacer(Modifier.height(8.dp))
        Text(error, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error)
    }
    Spacer(Modifier.height(16.dp))
    Row(Modifier.fillMaxWidth()) {
        TextButton(onClick = onCancel) { Text("Cancel") }
        Spacer(Modifier.weight(1f))
        Button(onClick = onSubmit, enabled = link.isNotBlank()) { Text("Find skills") }
    }
}

@Composable
private fun ChooseStep(
    step: InstallStep.Choose,
    onPick: (String) -> Unit,
    onCancel: () -> Unit,
) {
    Text("Several skills are available here. Pick the one to install.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(8.dp))
    step.items.forEach { (label, path) ->
        Text(
            displaySafeText(label),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.fillMaxWidth()
                .clickable { onPick(path) }
                .padding(vertical = 12.dp),
        )
    }
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth()) {
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onCancel) { Text("Cancel") }
    }
}

@Composable
private fun ReviewStep(
    step: InstallStep.Review,
    onCancel: () -> Unit,
    onInstall: () -> Unit,
) {
    val skill = step.payload.skill
    Text(displaySafeText(skill.name), style = MaterialTheme.typography.titleMedium)
    Text(displaySafeText(skill.description), style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    LabeledLine("Source", step.payload.sourceLabel)
    if (skill.allowedTools.isNotEmpty()) {
        LabeledLine("Allowed tools", skill.allowedTools.joinToString(", "))
    }
    if (step.payload.bundledPaths.isNotEmpty()) {
        Text("Bundled files", style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
        step.payload.bundledPaths.forEach { path ->
            Text(
                if (isBundledScript(path)) "$path (script)" else path,
                style = MaterialTheme.typography.bodySmall,
                color = if (isBundledScript(path)) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (step.replace) {
        Spacer(Modifier.height(16.dp))
        Text("A skill named ${skill.name} is already installed. Installing replaces it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error)
    }
    Spacer(Modifier.height(16.dp))
    Row(Modifier.fillMaxWidth()) {
        TextButton(onClick = onCancel) { Text("Cancel") }
        Spacer(Modifier.weight(1f))
        Button(onClick = onInstall) {
            Text(if (step.replace) "Replace" else "Install")
        }
    }
}

@Composable
private fun LabeledLine(label: String, value: String) {
    Column(Modifier.padding(top = 12.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(displaySafeText(value), style = MaterialTheme.typography.bodyMedium)
    }
}
