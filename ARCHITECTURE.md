# Rivet Architecture

Describes what exists today. Phase-by-phase growth is recorded in
MASTER_ROADMAP.md; anything not listed here is not in the tree.

## Current shape (Phase 3: Project Workspace)

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
    workspace/
        SafWorkspace.kt       # native SAF traversal and document operations
        WorkspaceSelection.kt # persisted tree grant and directory navigation
        WorkspacePath.kt      # validated workspace-relative names
        WorkspaceText.kt      # UTF-8 bounds, byte fingerprints, exact edits
        WorkspaceSearch.kt    # bounded, cancellable literal search
        WorkspaceEntry.kt     # metadata, capabilities, operation epochs
        WorkspaceFailure.kt   # safe user-facing failures
    ui/
        RivetApp.kt           # shell: nav, screen routing, settings entry
        RivetDestination.kt  # tab model
        Theme.kt             # dark color scheme, shape set
        files/               # workspace browser/editor, dialogs, FilesViewModel
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

## Workspace boundary

`ACTION_OPEN_DOCUMENT_TREE` runs through the Activity Result API. Only the
returned read/write grants are persisted. App-private `workspace` preferences
store the tree URI, last browsed directory, and open-file path; existing provider/chat stores
are unchanged. Canceling the picker changes nothing. Missing grants require
selection again; unavailable providers produce recoverable errors.

Every operation starts at the captured tree root and resolves validated names
through direct-child queries. Empty path means root. Absolute paths, dot
segments, empty components, separators in names, and control characters are
rejected, not repaired. URI/document IDs never become filesystem paths.
Directory queries project metadata and flags together, avoid per-child queries,
and sort directories first with locale-independent name ordering.

`SafWorkspace` exposes list/stat/read/write/create/delete/rename/move/search and
exact text patches. Mutations recheck current capabilities and duplicate names.
Root deletion/rename/move is forbidden. Moves use the provider's native API only;
there is no copy/delete fallback. Returned identities are resolved again after
creation, rename, and move. Unknown size/time metadata remains nullable.

## Text and mutation limits

Editing supports strict UTF-8, up to 1 MiB of bytes. NUL/control-byte or invalid
UTF-8 content is shown as metadata with an unsupported-editing message. Streams
are bounded even when providers omit sizes. File snapshots fingerprint original
bytes with SHA-256. Every save requires the previous fingerprint, rereads current
bytes before opening a truncating descriptor, and verifies bytes after writing.
Mismatches preserve the editor draft and report conflict. Exact patches require
a nonempty old-text match occurring exactly once (including overlapping matches);
edits are evaluated sequentially in memory, then committed only after all pass.

SAF has no universal atomic replace or compare-and-swap. An external writer can
still race between checking and committing; provider failures/process death
can leave partial data. Rivet does not advertise atomic writes. Mutations are
serialized within a workspace; after commit begins, coroutine cancellation does
not intentionally interrupt it. Keep backups of important files. Providers may
reject truncating writes, moves, or other mutations despite flags. No unsafe
fallback is attempted.

## Search and lifecycle

Literal case-sensitive search covers paths/names and text lines under the current
directory. Defaults: 1,000 files, 5,000 entries, 8 MiB total reads, 256 KiB per
file, 200 hits, and 240-character contexts. Binary/inaccessible entries are
skipped; limits are reported. Directory listings are capped at 5,000 entries.
Provider I/O runs on Dispatchers.IO. Cancellation signals and closing active
read descriptors support cancellation; providers can delay or ignore requests.

FilesViewModel survives rotation with navigation, draft, and active operation
state. Navigation and search epochs reject late results; saves/mutations block
editor navigation until completion. Back navigation/discard and deletion require
confirmation. Process death ends asynchronous work and loses unsaved drafts;
only the selected tree, directory, and file path restore (file bytes are reread). Whole project contents are not
persisted. There is no indexing database, model workspace access, agent loop,
terminal/shell/runtime integration, Git, or diff tracking.
