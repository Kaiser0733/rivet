# Rivet Architecture

Describes current boundaries and failure semantics. Phase history is in
MASTER_ROADMAP.md.

## Current shape (Chat-first product)

The `:app` module routes Chat and Settings, plus a contextual Processes screen.
`chat/` owns the turn's UI
state; `agent/` validates and runs tool calls; `workspace/` owns native SAF
operations; `runtime/` owns the private mirror, command process, Git inspection,
and checkpoints. `provider/` adapts three wire protocols; `storage/` owns
configuration, secrets, and SQLite conversations. Legacy DataStore chat decoding
remains for installed-history migration, not as another live chat stack.

`:app` depends directly on the vendored `:terminal-emulator` module because
`CommandProcess` loads its `libtermux` native library containing Rivet's
`command.c` launcher. The interactive Terminal and `:terminal-view` were
retired; the active command runner, mirror, and synchronization remain.
The application ID is `com.kaiser.rivet`.

## Provider boundary

`ProviderClient` has three protocol implementations with `listModels`,
`testConnection`, and `streamAgent`. All three transports share
`Http.kt` (OkHttp, SSE reader) and `Endpoints.kt`; OpenAI, OpenRouter, and
custom endpoints share one client class — only Anthropic and Gemini get
their own request shapes.

Streaming: the client accumulates and returns the full response text while
receiving deltas per chunk. Chat shows a throttled, temporary plain-text preview
during the request, then renders completed assistant prose with a small local
Markdown subset. User messages remain plain text; tool events, HTML, and active
links are not rendered. The SSE
loop lives on OkHttp's callback thread; coroutine cancellation cancels the
underlying call, which unblocks the reader. It requires each provider's native
completion marker and limits streamed lines to 128 KiB and each response to
1 MiB; error bodies are read only to a bounded prefix.

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
  `configs` / `active_id`, JSON-encoded list. Custom header values are
  AES-GCM encrypted before persistence and legacy plaintext values migrate on
  first read; the API key remains in its separate secret store.
- Coding sessions: SQLite stores provider-neutral full event rows, a bounded
  active model transcript, compaction summaries, and per-request usage. The
  previous DataStore `agent_messages` transcript imports once; still older
  `messages` text-chat records are decoded into agent events first. Their
  source bytes are retained. Sessions bind to the selected workspace and can be resumed or
  switched independently of provider/model selection.
- The stable Rivet security policy stays in the system role. Applicable
  `AGENTS.md` contents and the prior task summary are added as untrusted user
  context before the current request, and are rebuilt from their sources.
- API keys: app-private preferences contain versioned IV+ciphertext records.
  A non-exportable AES-256 key in AndroidKeyStore encrypts each value with
  AES/GCM/NoPadding; the provider id is authenticated as associated data.
  Missing or corrupt records read as absent and never trigger a store-wide
  deletion. Keys never enter provider configs, chat storage, logs, or saved
  state. The earlier development build's EncryptedSharedPreferences data is
  not migrated; its credentials must be entered once into the new store.

## UI shell

Single-activity Compose. Chat is the home surface; full-screen History and
Settings are secondary routes, with the provider editor nested in Settings.
Android's folder picker is launched from Chat. Runtime, file, Git, and
synchronization controls are not normal destinations. Processes is available
from Chat and the preview notification while Rivet-owned operations exist.
Chat shows completed
conversation text with a temporary streaming preview, contextual project
changes and Undo, and approval dialogs; provider-neutral tool events remain
durable but are not rendered as a log. A bounded model picker keeps fetched
provider lists searchable without burying its Save and Cancel actions. A local
appearance preference stores Rose/Dark choice and Rose intensity independently;
Rose is the default, and intensity applies only to Rose. System bars and
inherited text colors follow the selected palette.
History lists SQLite sessions with pinned sessions before recent sessions.
Portrait Chat uses available width with normal gutters. Every landscape
orientation, including phones, embeds shared History content beside Chat.
The sidebar uses 36% of available width, bounded to 220–320dp and no more than
40% on constrained windows. Settings and provider forms keep bounded reading
widths. Rotation preserves the composer
draft and ViewModels. System-initiated process death destroys the ViewModels
and terminates any active stream. A new process reloads completed provider
configuration and coding sessions from storage. An interrupted marker is
shown once; streams and approvals are never resumed or reconstructed.

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
when the mirror has no unsynchronized changes. Ask requires one-shot approval
for every mutation and command. Basic YOLO auto-authorizes routine file edits
and only the app's narrow read-only command classifications; other actions
still ask. YOLO skips approval only for allowed tools after explicit consent.
A repeated identical denial within the turn stays denied. Tool validation
rejects unknown names, extra or missing JSON fields, invalid paths, hashes, and
oversized input before SAF is called.
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
Repeating an unchanged deterministic blocker stops the turn; resolving a
mirror/sync blocker permits a later retry.

Autonomy is stored independently from provider and workspace configuration.
Ask confirms every mutation and command. Basic YOLO auto-authorizes ordinary
file edits and narrowly classified inspection commands; elevated commands,
downloads, destructive paths, and other non-routine actions still ask. YOLO
removes those prompts only after explicit consent. Tool effects separately
require the same checkpoint, workspace, result-headroom, and runtime guards in
all modes. Obvious app/package-management commands are blocked by a small
capability policy; this is not a shell sandbox.

The activity timeline is projected from provider-neutral call/result events
and temporary lifecycle signals. It contains no model reasoning and is not a
second transcript. Rivet's process registry is app-process state: it never
restores stale running claims after process death. Foreground commands can be
stopped from Processes; persistent local previews are stopped when the owning
service/app process ends. The registry caps active operations at four, keeps
eight completed records, and retains at most 8 KiB of output per command.

## Workspace boundary

`ACTION_OPEN_DOCUMENT_TREE` runs through the Activity Result API. Only the
returned read/write grants are persisted. App-private `workspace` preferences
store the tree URI and legacy last-browsed directory/open-file paths for
installed-data compatibility. Canceling the picker changes nothing. Missing grants require
selection again; unavailable providers produce recoverable errors.

Session binding uses the exact persisted tree identity. Chat distinguishes an
accessible different tree from a session whose project cannot currently be
restored; reselecting the exact bound identity keeps the session. Display names
and URI similarity never substitute for identity.

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

Text tool mutations support strict UTF-8, up to 1 MiB of bytes. NUL/control-byte or invalid
UTF-8 content is reported as unsupported. Streams
are bounded even when providers omit sizes. File snapshots fingerprint original
bytes with SHA-256. Every save requires the previous fingerprint, rereads current
bytes before opening a truncating descriptor, and verifies bytes after writing.
A prewrite mismatch refuses the write; a verification mismatch reports conflict.
Exact patches require
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

Chat retains project selection across rotation and process restart. The old
directory/file location preferences may still be read for compatibility, but
there is no routed file editor or draft. Whole project contents are not
persisted in preferences. Tool results never expose URIs or document IDs.
There is no repository index.

## Runtime boundary

The selected SAF tree remains the external workspace. `WorkspaceMirror`
streams regular file bytes into `files/runtime/workspaces/<sha256-tree-id>/current/worktree`
and keeps a compact path/type/size/SHA-256 baseline beside the worktree.
It rejects symlinks and special local entries. Before applying mirror changes, each SAF
entry must still match either the recorded baseline or the mirror's exact target.
This lets a retry resume completed writes after a partial provider failure; any
third state returns a conflict without overwriting it. Local creates,
modifications, and deletions then use the existing SAF path and provider
confirmation rules. A failed or interrupted sync retains the mirror, and Chat
offers a contextual retry that rechecks the selected project and SAF state.
No recursive file contents are retained in memory. An unresolved conflict keeps
both the pending mirror data and external project data until the project state
is resolved. Unsafe mirror entries also stop the turn instead of triggering
repeated tool calls.

`run_command` starts `/system/bin/sh -lc` with a workspace-relative cwd and
an explicit HOME/PATH/TMPDIR/PWD/LANG/TERM environment. HOME is under
the workspace's `files/runtime` area, not the app's credential/configuration area. Each agent
command follows the selected autonomy policy; stdout and stderr retain
bounded head/tail text, with bounded live snapshots in Processes. Exit status remains separate from sync status, and a
timeout or Stop terminates the process group. The shell shares Rivet's Android
UID: cwd checks are not a security sandbox. No API keys are exported.

The interactive PTY UI is no longer compiled. Agent commands use the native
launcher in `:terminal-emulator`, process-group cancellation, and automatic
mirror-to-SAF sync. A workspace switch cannot retarget an active command.
Android system utilities provide the initial command set. No Termux
installation, package manager, or app-data ELF execution is present.
The inspected modern `termux-exec` linker/interception approach is reserved
for future packaged binaries; direct app-data execution is not assumed.

`start_preview` serves selected SAF files using bounded streaming HTTP bound
only to `127.0.0.1`, with GET/HEAD, no listing, and no execution. It uses the
API 34+ `specialUse` foreground-service type with a user-visible notification;
no boot receiver or process resurrection is used. Each preview response is
capped at 64 MiB with at most four requests in flight. `download_file` uses a
separate cookie-free HTTPS client, at most five revalidated redirects, and a
64 MiB streaming cap. Downloads are staged in app cache and committed through
the SAF workspace with hash checks, workspace validation, and the normal
checkpoint path; downloaded data is never run or installed.

## Repository, checkpoints, and context

JGit inspects only a real `.git` directory at the selected worktree root.
`git_status` and `git_diff` never stage or alter the user's repository. Diff
output is capped at 16 KiB and 20 files. Android's system shell does not supply
Git; Rivet's inspection works without a separate Git executable.

After authorization and before the first workspace mutation in an agent turn,
`TurnCheckpoint` streams a pre-change ZIP into app-private storage. It excludes
`.git`, tracks a post-change manifest, and retains at most three completed
checkpoints within 2 GiB. Undo requires an explicit UI confirmation and an
unchanged post-turn worktree. Restoration passes through the mirror's SAF
conflict checks. A checkpoint that cannot be created prevents the mutation.

SQLite keeps full events independently of the active transcript. Before a
provider request, Rivet accounts for the assembled messages, system text, and
tool schemas. A listed provider model may supply a scoped input limit; unknown
models use a conservative planning threshold and reactive overflow handling.
Compatible reported input usage anchors later estimates. Context pressure
first prunes old successful tool bodies, then replaces older complete groups
with a bounded structured task state while retaining a recent verbatim tail.
Compaction stores the canonical event-prefix and active-projection hashes in
the same transaction as the new summary. Local bounded attempt records explain
reductions and failures. One clear provider overflow may retry a materially
smaller request without replaying completed tools. Rivet policy, tools, and
applicable `AGENTS.md` instructions are rebuilt outside the task state, which
cannot authorize actions or assert current workspace truth.
