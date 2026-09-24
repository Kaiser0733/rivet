# Rivet Architecture

Describes what exists today. Phase-by-phase growth is recorded in
MASTER_ROADMAP.md; anything not listed here is not in the tree.

## Current shape (Phase 7: Chat-first product)

The `:app` module uses vendored `:terminal-emulator` and `:terminal-view`
modules. The application ID remains `com.kaiser.rivet`.

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
        ChatViewModel.kt     # agent orchestration, snapshots, cancellation
    agent/
        AgentProtocol.kt      # persisted provider-neutral transcript
        AgentLoop.kt          # capped model/tool/result loop
        AgentApprovalGate.kt  # one-shot mutation approval authority
        AgentToolExecutor.kt  # strict workspace tool schemas and validation
        SafAgentWorkspace.kt  # adapter to the trusted SAF boundary
        AgentContext.kt       # bounded active-context planning
        ProjectInstructions.kt # bounded root-to-target AGENTS.md loading
    runtime/
        WorkspaceMirror.kt    # streamed SAF mirror and baseline
        RuntimeController.kt  # command/terminal workspace association
        CommandProcess.kt     # bounded command capture and cleanup
        TerminalViewModel.kt  # retained PTY session and explicit sync
        GitInspection.kt      # read-only JGit status and bounded diff
        TurnCheckpoint.kt     # private pre-turn archive and undo fingerprints
    storage/
        ProviderStore.kt     # DataStore: provider configs + active id
        SecretStore.kt       # Android Keystore + AES-GCM API-key storage
        ChatStore.kt         # legacy chat plus migrated agent session
        CodingSessions.kt    # SQLite full events, active context, usage
    workspace/
        SafWorkspace.kt       # native SAF traversal and document operations
        WorkspaceSelection.kt # persisted tree grant and directory navigation
        WorkspacePath.kt      # validated workspace-relative names
        WorkspaceText.kt      # UTF-8 bounds, byte fingerprints, exact edits
        WorkspaceSearch.kt    # bounded, cancellable literal search
        WorkspaceEntry.kt     # metadata, capabilities, operation epochs
        WorkspaceFailure.kt   # safe user-facing failures
    ui/
        RivetApp.kt           # Chat and Settings routing
        RivetDestination.kt  # two normal surfaces
        Theme.kt             # dark color scheme, shape set
        files/               # retained workspace browser/editor code; not routed
        chat/ChatScreen.kt   # conversation, project choice, approval, Undo
        terminal/TerminalScreen.kt # retained PTY UI; not routed
        changes/             # retained Git/checkpoint review code; not routed
        provider/SettingsScreen.kt    # provider list, add/edit/delete
        provider/ProviderEditor.kt    # provider form, test, fetch models
        provider/ProvidersViewModel.kt
        provider/ProviderEditorState.kt
```

## Provider boundary

`ProviderClient` is the one interface with multiple implementations:
`listModels`, `testConnection`, `streamChat`, and `streamAgent`. All three transports share
`Http.kt` (OkHttp, SSE reader) and `Endpoints.kt`; OpenAI, OpenRouter, and
custom endpoints share one client class — only Anthropic and Gemini get
their own request shapes.

Streaming: the client accumulates and returns the full response text while
invoking `onDelta` per chunk; the ViewModel appends into UI state. The SSE
loop lives on OkHttp's callback thread; coroutine cancellation cancels the
underlying call, which unblocks the reader.

The agent transcript represents user and assistant text, structured tool calls,
and correlated tool results without provider wire syntax. Adapters translate it
to Chat Completions tools, Anthropic `tool_use` / `tool_result` blocks, or Gemini
function calls and responses. Stream parsers retain call IDs, multiple calls,
fragmented arguments, Anthropic thinking blocks, and Gemini thought signatures.

Send-time snapshot: `ChatViewModel.send` captures provider, model, reasoning,
credential, and workspace before
the request starts. Switching provider/model mid-stream affects the next
message only; the active request completes (or is stopped) on its own.

## Persistence

- Provider configs + active selection: DataStore Preferences, keys
  `configs` / `active_id`, JSON-encoded list.
- Coding sessions: SQLite stores provider-neutral full event rows, a bounded
  active model transcript, compaction summaries, and per-request usage. The
  previous DataStore `agent_messages` transcript imports once; its source is
  retained. Sessions bind to the selected workspace and can be resumed or
  switched independently of provider/model selection.
- API keys: app-private preferences contain versioned IV+ciphertext records.
  A non-exportable AES-256 key in AndroidKeyStore encrypts each value with
  AES/GCM/NoPadding; the provider id is authenticated as associated data.
  Missing or corrupt records read as absent and never trigger a store-wide
  deletion. Keys never enter provider configs, chat storage, logs, or saved
  state. The earlier development build's EncryptedSharedPreferences data is
  not migrated; its credentials must be entered once into the new store.

## UI shell

Single-activity Compose. Chat is the normal working surface; Settings is
secondary. Android's folder picker is launched from Chat. Runtime, file, Git,
and synchronization controls are not normal destinations. Chat shows completed
conversation text, contextual project changes and Undo, and approval dialogs;
provider-neutral tool events remain durable but are not rendered as a log.
Content width is capped for tablet layouts. Rotation preserves the composer
draft and ViewModels. System-initiated
process death destroys the ViewModels and terminates any active stream. A new
process reloads completed provider configuration and coding sessions from
storage. An interrupted marker is shown once; streams and approvals are never
resumed or reconstructed.

## Agent execution boundary

`AgentLoop` has emergency runaway ceilings of 200 model responses and 1,000
requested tools per turn. There is no cumulative tool-output quota. Individual
results remain capped at 24 KiB of encoded UTF-8 JSON; the active model
transcript remains capped at 512 KiB. Full event history has no aggregate
512 KiB limit. Read-only results are checked at their actual size.
Before approval, mutations reserve space for their bounded result contract and
all remaining correlated results. Session exhaustion stops the turn without
executing the mutation. Workspace file text is untrusted project data; tool
results establish observed state, not higher-priority instructions.
Read-only `list_directory`, `read_file`, and `search_files` calls run directly
when the mirror has no unsynchronized changes. Every write, exact patch,
create, rename, move, delete, and agent `run_command` waits for a one-shot
Approve or Deny decision. A repeated identical denial within the turn stays
denied. Tool validation rejects unknown names, extra or missing JSON fields,
invalid paths, hashes, and oversized input before SAF is called.
The turn tracks confirmed created paths and carries that state through successful
renames and moves. Delete, rename, and move of paths not known to be created in
the turn receive a stronger approval showing the path, type, and known size.
Delete remains permanent: document providers need not support native moves, and
a portable recovery record cannot be guaranteed within the current workspace.

The workspace identity is checked before every tool and again after approval.
Changing the selected tree stops the turn instead of redirecting work. Stop
cancels the provider request, pending approval, future calls, and cancellable
reads. A SAF commit that already began retains Phase 3 non-cancellable commit
semantics, and its completed result is persisted. Tool errors remain correlated.
Repeating an unchanged deterministic blocker stops the turn; a changed
Terminal/sync state permits a later retry.

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
creation, rename, and move, and agent tool results report the provider-confirmed
path. Unknown size/time metadata remains nullable.

Empty files are created with `application/octet-stream` to avoid MIME-driven
suffixes on coding filenames. If a provider normalizes the name and supports
rename, one correction is attempted and its returned identity is verified.
Creation is never retried; an uncorrectable name is returned as the actual path
alongside the requested path, with the empty-file SHA and size preserved.
Trailing-dot and blank names are rejected before creation or rename. Provider
MIME guesses only block known binary filename types; other files must pass
bounded strict UTF-8 validation. A newly created file is inspected without a
MIME veto so its confirmed contents can supply the hash handoff.

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
directory, or exactly the requested file. Defaults: 1,000 files, 5,000 entries, 8 MiB total reads, 256 KiB per
file, 200 hits, and 240-character contexts. Binary/inaccessible entries are
skipped; limits and bounded scan totals are reported. Directory listings are
capped at 5,000 entries.
Physical mutation tests must use a disposable SAF workspace with generated read,
binary, search, rename, move, delete, and filename fixtures. Never run destructive
self-tests against an existing user project.
Provider I/O runs on Dispatchers.IO. Cancellation signals and closing active
read descriptors support cancellation; providers can delay or ignore requests.

FilesViewModel survives rotation with navigation, draft, and active operation
state. Navigation and search epochs reject late results; saves/mutations block
editor navigation until completion. Back navigation/discard and deletion require
confirmation. Process death ends asynchronous work and loses unsaved drafts;
only the selected tree, directory, and file path restore (file bytes are reread).
Whole project contents are not persisted in preferences. Tool results never
expose URIs or document IDs. There is no repository index.

## Runtime boundary

The selected SAF tree remains the external workspace. `WorkspaceMirror`
streams regular file bytes into `files/runtime/workspaces/<sha256-tree-id>/current/worktree`
and keeps a compact path/type/size/SHA-256 baseline beside the worktree.
It rejects symlinks and special local entries. Before applying mirror changes, the SAF
baseline is checked; any external change returns a conflict and leaves local
work in the mirror. Local creates, modifications, and deletions then use the
existing SAF path and provider confirmation rules. A failed or interrupted
sync retains the mirror; a later stale baseline cannot silently replay writes.
No recursive file contents are retained in memory.

`run_command` starts `/system/bin/sh -lc` with a workspace-relative cwd and
an explicit HOME/PATH/TMPDIR/PWD/LANG/TERM environment. HOME is under
the workspace's `files/runtime` area, not the app's credential/configuration area. Each agent
command requires the existing one-shot approval; stdout and stderr retain
bounded head/tail text, exit status remains separate from sync status, and a
timeout or Stop terminates the process group. The shell shares Rivet's Android
UID: cwd checks are not a security sandbox. No API keys are exported.

The retained Terminal implementation uses a PTY, the vendored Termux terminal
emulator/view, and one `/system/bin/sh` session retained across rotation. The
Terminal screen is not routed in the Phase 7 product. Agent commands still use
the runtime, process-group cancellation, and automatic mirror-to-SAF sync. A
workspace switch cannot retarget an active command. Android system utilities
provide the initial command set. No
Termux installation, package manager, or app-data ELF execution is present.
The inspected modern `termux-exec` linker/interception approach is reserved
for future packaged binaries; direct app-data execution is not assumed.

## Repository, checkpoints, and context

JGit inspects only a real `.git` directory at the selected worktree root.
`git_status` and `git_diff` never stage or alter the user's repository. Diff
output is capped at 16 KiB and 20 files. Android's system shell does not supply
Git; Rivet's inspection works without a separate Git executable.

After approval and before the first workspace mutation in an agent turn,
`TurnCheckpoint` streams a pre-change ZIP into app-private storage. It excludes
`.git`, tracks a post-change manifest, and retains at most three completed
checkpoints within 2 GiB. Undo requires an explicit UI confirmation and an
unchanged post-turn worktree. Restoration passes through the mirror's SAF
conflict checks. A checkpoint that cannot be created prevents the mutation.

SQLite keeps full events independently of the active transcript. Context
pressure first removes older complete call/result groups from active context,
then stores a bounded task-state summary; full rows remain. Recent complete
groups stay verbatim. A clear provider context-overflow response may trigger
one smaller model request, never a replay of completed tools. Rivet policy,
approvals, workspace identity, and bounded applicable `AGENTS.md` instructions
are rebuilt outside the summary on each request. Usage is recorded as reported,
estimated from a compatible reported anchor, or unknown; model context-window
size is not inferred from an unverified model-name table.
