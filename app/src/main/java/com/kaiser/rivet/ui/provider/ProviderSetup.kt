package com.kaiser.rivet.ui.provider

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.kaiser.rivet.R
import com.kaiser.rivet.provider.ChatGptSignInResult
import com.kaiser.rivet.provider.ChatGptSignInSession
import com.kaiser.rivet.provider.resolveSignInClientId
import com.kaiser.rivet.provider.runSignIn
import com.kaiser.rivet.storage.HostIdStore
import com.kaiser.rivet.ui.RivetOutlinedButton
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ProviderSetup(providersViewModel: ProvidersViewModel, onOpenModels: () -> Unit, showActions: Boolean) {
    var showKeyInput by remember { mutableStateOf(false) }
    var keyInput by remember { mutableStateOf("") }
    var keyError by remember { mutableStateOf<String?>(null) }
    val onIHaveAKey: () -> Unit = { showKeyInput = true }
    var showStartFree by remember { mutableStateOf(false) }
    val planNotGrantedMsg = stringResource(R.string.chatgpt_plan_not_granted)
    val networkMsg = stringResource(R.string.chatgpt_signin_network)
    val failedMsg = stringResource(R.string.chatgpt_signin_failed)
    val expiredMsg = stringResource(R.string.chatgpt_signin_expired)
    val timeoutMsg = stringResource(R.string.chatgpt_signin_timeout)
    val chatScope = rememberCoroutineScope()
    val context = LocalContext.current
    var showChatGptSignInError by remember { mutableStateOf<String?>(null) }
    var showChatGptIntro by remember { mutableStateOf(false) }
    // In-flight sign-in handle: lets a re-tap cancel the previous attempt
    // instead of orphaning its loopback listener.
    var chatGptSession by remember { mutableStateOf<ChatGptSignInSession?>(null) }
    // Cancel sign-in on system back and if the composable is torn down
    // before the coroutine clears the reference.
    BackHandler(enabled = chatGptSession != null) { chatGptSession?.cancel() }
    DisposableEffect(chatGptSession) {
        val session = chatGptSession
        onDispose { session?.cancel() }
    }

    if (showActions) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = {
            // Cancel any in-flight attempt first: no orphaned
            // loopback listeners from repeated taps.
            chatGptSession?.cancel()
            chatScope.launch {
                val session = ChatGptSignInSession()
                chatGptSession = session
                try {
                    showChatGptSignInError = null
                    val hostIds = HostIdStore(context)
                    val result = runSignIn(
                        hostId = hostIds.getOrCreateHostId(),
                        onAuthorizeUrl = { url ->
                            try {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            } catch (_: Exception) { }
                        },
                        clientId = resolveSignInClientId(
                            bundleClientId = null,
                            pendingClientId = hostIds.pendingClientId(),
                        ),
                        session = session,
                        hostIds = hostIds,
                    )
                    when (result) {
                        is ChatGptSignInResult.Success -> {
                            providersViewModel.saveChatGptPlanProvider(
                                result.bundle,
                                onSaved = {
                                    if (providersViewModel.shouldShowChatGptPlanIntro()) {
                                        providersViewModel.markChatGptPlanIntroSeen()
                                        showChatGptIntro = true
                                    }
                                },
                                onError = { msg -> showChatGptSignInError = msg },
                            )
                        }
                        ChatGptSignInResult.UserCancelled -> { }
                        ChatGptSignInResult.PlanNotGranted -> showChatGptSignInError = planNotGrantedMsg
                        is ChatGptSignInResult.NetworkError -> showChatGptSignInError = networkMsg
                        ChatGptSignInResult.InvalidCallback -> showChatGptSignInError = failedMsg
                        ChatGptSignInResult.NeedsReauth -> showChatGptSignInError = expiredMsg
                        ChatGptSignInResult.TimedOut -> showChatGptSignInError = timeoutMsg
                        is ChatGptSignInResult.ServerRejected ->
                            showChatGptSignInError = context.getString(
                                R.string.chatgpt_signin_server_error, result.code)
                    }
                } finally {
                    if (chatGptSession === session) chatGptSession = null
                }
            }
        }) { Text(stringResource(R.string.continue_with_chatgpt)) }
        TextButton(onClick = { showStartFree = true }) { Text(stringResource(R.string.start_free)) }
        TextButton(onClick = onOpenModels) { Text("Start offline") }
    }
    if (showStartFree) {
        val context = LocalContext.current
        AlertDialog(onDismissRequest = { showStartFree = false },
            title = { Text(stringResource(R.string.start_free_title),
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Default)) },
            text = { Text(stringResource(R.string.start_free_body),
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Default)) },
            confirmButton = {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(onClick = {
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://openrouter.ai"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (_: Exception) { }
                    }) { Text(stringResource(R.string.open_openrouter)) }
                    RivetOutlinedButton(onClick = { showStartFree = false; onIHaveAKey() }) {
                        Text(stringResource(R.string.i_have_a_key))
                    }
                }
            },
            dismissButton = { TextButton(onClick = { showStartFree = false }) {
                Text(stringResource(R.string.close))
            } })
    }
    if (showKeyInput) {
        val blankKeyMessage = stringResource(R.string.free_key_blank)
        AlertDialog(onDismissRequest = { showKeyInput = false; keyInput = ""; keyError = null },
            title = { Text(stringResource(R.string.free_key_title),
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Default)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = keyInput,
                        onValueChange = { keyInput = it; keyError = null },
                        label = { Text(stringResource(R.string.free_key_label)) },
                        placeholder = { Text(stringResource(R.string.free_key_hint)) },
                        singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
                            autoCorrect = false,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    keyError?.let { message ->
                        Text(message, color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                RivetOutlinedButton(onClick = {
                    val trimmed = keyInput.trim()
                    if (trimmed.isBlank()) {
                        keyError = blankKeyMessage
                        return@RivetOutlinedButton
                    }
                    providersViewModel.saveFreeProvider(
                        trimmed,
                        onSaved = {
                            showKeyInput = false
                            keyInput = ""
                            keyError = null
                        },
                        onError = { message -> keyError = message },
                    )
                }) { Text(stringResource(R.string.provider_save)) }
            },
            dismissButton = { TextButton(onClick = { showKeyInput = false; keyInput = ""; keyError = null }) {
                Text(stringResource(R.string.provider_cancel))
            } })
    }
    if (showChatGptSignInError != null) {
        AlertDialog(
            onDismissRequest = { showChatGptSignInError = null },
            text = { Text(showChatGptSignInError.orEmpty()) },
            confirmButton = {
                TextButton(onClick = { showChatGptSignInError = null }) {
                    Text(stringResource(R.string.close))
                }
            },
        )
    }
    if (showChatGptIntro) {
        AlertDialog(
            onDismissRequest = { showChatGptIntro = false },
            title = { Text(stringResource(R.string.chatgpt_plan_intro_title),
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Default)) },
            text = { Text(stringResource(R.string.chatgpt_plan_intro_body),
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Default)) },
            confirmButton = {
                RivetOutlinedButton(onClick = { showChatGptIntro = false }) {
                    Text(stringResource(R.string.got_it))
                }
            },
        )
    }
}
