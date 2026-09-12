# Rivet Architecture

Describes what exists today. Phase-by-phase growth is recorded in
MASTER_ROADMAP.md; anything not listed here is not in the tree.

## Current shape (Phase 2: Provider Engine)

One Android module, `:app`, package `com.kaiser.rivet`.

```
app/src/main/java/com/kaiser/rivet/
    MainActivity.kt            # single activity, supplies ViewModels + version
    provider/
        ProviderClient.kt     # client interface + request/result types + factory fn
        ProviderConfig.kt     # config model, types, reasoning levels, header rules
        ProviderError.kt     # sealed error taxonomy + user-facing text
        Endpoints.kt         # all URL construction, centralized
        Http.kt              # OkHttp client, await(), SSE reader
        Json.kt               # tolerant JSON accessors
        OpenAiCompatibleClient.kt  # OpenAI / OpenRouter / compatible endpoints
        AnthropicClient.kt   # native Messages API
        GeminiClient.kt      # native generateContent
    chat/
        ChatMessage.kt        # message + role model
        ChatViewModel.kt     # send/cancel/state; send-time provider snapshot
    storage/
        ProviderStore.kt     # DataStore: provider configs + active id
        SecretStore.kt       # Android Keystore + AES-GCM API-key storage
        ChatStore.kt         # DataStore: one persistent conversation
    ui/
        RivetApp.kt           # shell: nav, screen routing, settings entry
        RivetDestination.kt  # tab model
        Theme.kt             # dark color scheme, shape set
        chat/ChatScreen.kt   # message list, input, model selector
        provider/SettingsScreen.kt    # provider list, add/edit/delete
        provider/ProviderEditor.kt    # provider form, test, fetch models
        provider/ProvidersViewModel.kt
        provider/ProviderEditorState.kt
```

## Provider boundary

`ProviderClient` is the one interface with multiple implementations:
`listModels`, `testConnection`, `streamChat`. All three transports share
`Http.kt` (OkHttp, SSE reader) and `Endpoints.kt`; OpenAI, OpenRouter, and
custom endpoints share one client class — only Anthropic and Gemini get
their own request shapes.

Streaming: the client accumulates and returns the full response text while
invoking `onDelta` per chunk; the ViewModel appends into UI state. The SSE
loop lives on OkHttp's callback thread; coroutine cancellation cancels the
underlying call, which unblocks the reader.

Send-time snapshot: `ChatViewModel.send` captures provider + model before
the request starts. Switching provider/model mid-stream affects the next
message only; the active request completes (or is stopped) on its own.

## Persistence

- Provider configs + active selection: DataStore Preferences, keys
  `configs` / `active_id`, JSON-encoded list.
- Chat history: DataStore Preferences, key `messages`, JSON list. One
  conversation.
- API keys: app-private preferences contain versioned IV+ciphertext records.
  A non-exportable AES-256 key in AndroidKeyStore encrypts each value with
  AES/GCM/NoPadding; the provider id is authenticated as associated data.
  Missing or corrupt records read as absent and never trigger a store-wide
  deletion. Keys never enter provider configs, chat storage, logs, or saved
  state. The earlier development build's EncryptedSharedPreferences data is
  not migrated; its credentials must be entered once into the new store.

## UI shell

Single-activity Compose. Width >= 600dp uses NavigationRail, else
NavigationBar. Chat and Settings screens cap content width at 640dp on
wide layouts instead of stretching phone-width fields across a tablet.
Rotation: ViewModels and their active work survive activity recreation, while
`rememberSaveable` may restore the selected tab and screen. System-initiated
process death destroys the ViewModels and terminates any active stream. A new
process reloads completed provider configuration and chat history from
DataStore; streams are not resumed or reconstructed.

## Planned boundaries

`workspace/` — project filesystem access and change tracking (Phase 3).
It does not exist yet; nothing references it.
