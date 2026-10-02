# Architecture

## App shape

Rivet is a single-activity Compose app. Chat is home; History and Settings are
secondary routes, with the provider editor inside Settings and Processes
available while Rivet-owned operations exist. Android's folder picker starts
from Chat. There is no file editor, Git dashboard, or interactive Terminal.

`:app` packages separate `agent/`, `chat/`, `provider/`, `storage/`, `workspace/`,
and `runtime/` responsibilities. Its direct `:terminal-emulator` dependency
supplies `libtermux`, including Rivet's native `command.c` launcher. Removing
terminal presentation does not remove that command dependency. The application
ID is `com.kaiser.rivet`.

Rose/Dark appearance and Rose intensity are app-local preferences; intensity
does not change device brightness. Portrait Chat uses available width.
Landscape embeds the same History content beside Chat: 36% of the width,
bounded to 220–320dp and at most 40% on constrained windows. Settings and forms
keep bounded reading widths. Rotation retains the draft and ViewModels.
Completed assistant prose renders a local Markdown subset; user text is plain,
and tool events, HTML, and active links are not rendered. Streaming text is a
throttled temporary preview.

## Provider boundary

`ProviderClient` supplies model listing, connection tests, and structured agent
streaming. OpenAI, OpenRouter, and compatible endpoints share Chat Completions;
Anthropic and Gemini have native adapters. Provider networking shares OkHttp,
endpoint assembly, and a bounded SSE reader. It retains IDs, fragmented and
multiple calls, Anthropic thinking blocks, and Gemini thought signatures.

Each parser requires its native completion marker. SSE lines are capped at
128 KiB and a response at 1 MiB; error bodies use a bounded prefix. Cancellation
cancels the underlying OkHttp call and unblocks the reader. Provider failures
are classified without displaying wire bodies in ordinary Chat. User requests
are not automatically retried; one explicit context-overflow recovery is
described below.

Send captures provider, model, reasoning, credential, and exact workspace
identity. Changing a selection affects the next message, not an active request.
Unknown provider/model capabilities omit optional reasoning fields rather than
assuming support. Credentials never enter tool arguments or the command
environment.

## Agent and approval boundaries

Canonical events contain provider-neutral text, calls, correlated results, and
internal context. Only adapters construct provider wire syntax; assistant prose
is never parsed as executable tool instructions. Workspace content is untrusted
data. Tool observations establish state, not higher-priority instructions.

The loop has emergency ceilings of 200 model responses and 1,000 tools per turn,
with no cumulative output quota. Individual results are limited to 24 KiB of
encoded UTF-8 JSON, and active model context to 512 KiB. Before a mutation,
Rivet reserves room for its bounded correlated result and remaining results.
Read-only results are measured at their actual size. Exhausted context stops
before the side effect instead of losing durable outcome evidence.

App-owned autonomy is independent of provider and project configuration:

- **Ask** requires fresh one-shot approval for mutations and commands.
- **Basic YOLO** auto-authorizes routine file edits, owned process stops, and
  narrowly classified inspection commands; other actions still ask.
- **YOLO** skips confirmation for allowed tools after explicit consent.

All modes retain tool-effect, checkpoint, workspace, runtime, and persistence
checks. Unknown stored modes fall back to Ask. Repeated identical denials remain
denied in that turn. A small capability policy blocks obvious app/package
management commands; it is not a shell sandbox.

Arguments are checked for known tools, allowed fields, bounds, relative paths,
and hashes before operations begin. Confirmed created paths are tracked within
the turn through successful rename/move. Delete, rename, or move of a path not
known to be turn-created receives stronger approval with type and known size.
Delete is permanent: SAF providers do not guarantee portable trash/move support.
Checkpoints provide a separate guarded recovery mechanism.

Exact workspace identity is checked before tools and again after approval.
Changing the selected tree stops the turn. An unrecoverable runtime preflight
failure stops before approval or execution; the model cannot reason around it
and claim that a command ran. Repeated unchanged deterministic failures stop
no-progress loops, while resolved state permits a later retry.

Stop cancels model requests, pending approval, later tools, and cancellable
reads. Once a SAF commit has begun, cancellation does not intentionally interrupt
it; its completed correlated result is persisted. Completed side effects are
not described as rolled back.

## Workspace and SAF

The selected Android document tree is the external authority. Only returned
read/write grants are persisted; canceling selection changes nothing. Missing
grants require selection again, and unavailable providers fail recoverably.
Sessions bind to the exact tree identity, not its display name or URI similarity.
Reselecting the same accessible identity retains the conversation; switching
projects cannot silently retarget an old turn.

Paths resolve validated direct-child names from the captured root. Empty path
means root. Absolute paths, dot segments, empty components, separators in names,
and controls are rejected. Document IDs and URIs never become filesystem paths.
Listings query metadata and capabilities together, sort directories first with
locale-independent names, and stop at 5,000 entries.

`SafWorkspace` owns list/stat/read/search and native mutations. Capability and
collision checks run again at mutation time. Root delete/rename/move is forbidden.
Moves use only the provider's native operation, with no copy/delete fallback.
Returned create/rename/move identities are resolved again and their confirmed
paths are authoritative; unknown sizes/times remain nullable.

Empty file creation uses `application/octet-stream` to avoid MIME-driven suffixes.
If a provider normalizes a valid name and can rename, Rivet tries one verified
correction. Creation is never retried. An uncorrectable name is reported with
both actual and requested paths, plus inspected empty-file SHA and size. If
follow-up inspection is unavailable, the successful create reports that
inspection error instead of an ordinary failure. Blank and
trailing-dot names are rejected before mutation. Newly created files are
inspected without a MIME veto, so classification cannot turn committed creation
into an ordinary failure.

Text operations accept at most 1 MiB of strict UTF-8 bytes. Streams remain
bounded when size metadata is absent. MIME blocks an early read only with
matching known binary filename evidence; a provider calling `.ts` video does
not make valid source text unusable. NUL/control bytes and malformed UTF-8 are
unsupported. Agent reads use at most 8 KiB of content per chunk, safe UTF-8
boundaries, full-file SHA, next offset, and explicit EOF.

Writes require the prior original-byte SHA-256, reread before opening a
truncating descriptor, and verify resulting bytes. Prewrite and verification
mismatches report conflicts. Exact patches require a nonempty old-text match
occurring exactly once, including overlaps; sequential edits are validated in
memory before committing.

SAF provides neither atomic replace nor portable compare-and-swap. An external
writer can race the check/commit boundary, and provider failure or process death
can leave partial data. Mutations serialize within a workspace, and unsafe
fallbacks are not attempted. Rivet does not advertise atomic writes.

Literal case-sensitive search targets one file or a bounded recursive directory.
Defaults are 1,000 files, 5,000 entries, 8 MiB read total, 256 KiB per file,
200 hits, and 240-character contexts. Binary/inaccessible entries are skipped;
limits and scan counters are reported. There is no repository index.

## Runtime mirror and commands

`WorkspaceMirror` streams regular bytes into an app-private tree-specific
worktree, with a path/type/size/SHA baseline beside it. Symlinks and special local
entries are rejected. Before syncing changes, each SAF entry must match the
baseline or the exact mirror target. The latter permits retry after a partial
sync; any third state stops without overwriting external work.

Failed or interrupted sync preserves pending private and external data. Pending
private changes block ordinary tools until safely synced or discarded. A
contextual retry rechecks both. Explicitly confirmed discard instead reads
current SAF data into a verified replacement and atomically swaps the private
copy without writing to SAF. An installed discard marker distinguishes
approved abandonment from unapproved dirty-data loss after process death.
Discard does not alter checkpoint history. Project contents are streamed, not
retained as a whole in memory.

`run_command` launches `/system/bin/sh -lc` with a workspace-relative cwd and
explicit HOME/PATH/TMPDIR/PWD/LANG/TERM. HOME belongs to the runtime area, not the
credential/configuration area. Output retains bounded head/tail text; exit
status and sync status are separate. Timeout or Stop terminates the process
group. Workspace switching cannot retarget an active command. Android utilities
supply the initial command set; no external Termux, package manager, or app-data
ELF execution is required.

The shell shares Rivet's Android UID. Cwd checks are not an OS sandbox, and
commands can access app-private files available to that UID. No stored API keys
are exported. Generic persistent shell processes are deferred because they
could race synchronization and checkpoints.

Processes is an in-memory ownership registry, not a second transcript. It caps
active operations at four, retains eight completed records, and keeps at most
8 KiB per command. Activity projects correlated events and bounded lifecycle
signals, never model reasoning. Download success requires a confirmed file
receipt; an error payload cannot become success merely because its envelope
lacks an error flag. Running records do not resurrect after process death.

## Git, checkpoints, and Undo

JGit inspects only a real root `.git` directory in the private worktree; gitfile
links are rejected because they can escape it. Status and diff never stage,
commit, reset, or sync Git metadata. Diff output is capped at 16 KiB and 20 files.
The system shell does not supply Git; inspection needs no Git executable.

After authorization and before the first workspace mutation of a turn,
`TurnCheckpoint` streams one pre-change ZIP into app-private storage, excluding
`.git`. It records a post-change manifest and retains at most three completed
checkpoints within 2 GiB. Checkpoint failure prevents a protected mutation.
Commands participate because they can modify arbitrary project content.

Undo requires explicit confirmation and an unchanged post-turn worktree.
Restore uses mirror/SAF conflict checks, preserving newer external data.
The contextual Undo action is not reconstructed after process restart.
Checkpoints are recovery aids, not a backup system.

## Preview and project downloads

Static previews stream selected SAF files over GET/HEAD, with no listing or
execution, bound only to `127.0.0.1`. A preview root is project-relative; its entry
may be root-relative (`index.html`) or equivalent project-relative
(`site/index.html` for root `site`). Assets stay under the same root. Each
response is capped at 64 MiB, with at most four requests in flight.

A non-exported `specialUse` foreground service owns preview lifetime, with a
visible notification and no boot restart. It cannot retarget a preview to a
replacement project; servers end when their service/app process ends.

`download_file` uses a separate cookie-free HTTPS client, at most five
revalidated redirects, and a 64 MiB streaming cap. It stages in app cache,
checks optional content SHA, then commits through SAF hash checks, workspace
validation, and the normal checkpoint path. Downloaded files are not run or
installed.

## Persistence and credentials

Provider configuration and active selection use DataStore's stable `configs`
and `active_id` keys. Custom header values are AES-GCM encrypted before storage;
legacy plaintext headers migrate on first read. API keys stay separate.

`SecretStore` uses a non-exportable AndroidKeyStore AES-256 key and
AES/GCM/NoPadding, authenticating provider ID as associated data. App-private
preferences hold versioned IV/ciphertext records. Missing/corrupt records affect
only that credential; errors never wipe unrelated keys. Credentials do not
enter conversation storage, logs, or saved state. An early development
EncryptedSharedPreferences format is not migrated; those installs must reenter
keys once.

SQLite stores full provider-neutral event rows, bounded active context,
compaction metadata, usage, and session headers. Full history has no aggregate
512 KiB cap and is paged for display. The old DataStore `agent_messages` imports
once, decoding earlier `messages` records where needed and retaining source
bytes. Pinned sessions sort before recency without changing activity time.
Selected tree and legacy browsing identity preferences remain for compatibility;
whole project contents are not stored in preferences.

Process death ends streams and pending approvals. Completed events reload, an
interrupted marker is shown once, and neither approval nor execution authority
is reconstructed from history.

## Project instructions, context, and usage

Stable system policy is independent of summaries. Applicable `AGENTS.md` files
are root-to-target observations loaded only for touched scopes: at most eight
files, 8 KiB per file, and 32 KiB total, with bounded discovery depth/counts. Typed
internal events are projected as untrusted native user data, without rewriting
human messages or exposing persistent metadata on the wire. Unchanged guidance
deduplicates; changes and removals append in canonical order, bound to the exact
workspace. Project instructions cannot change approval or security policy.

Before each request, Rivet accounts for assembled messages, system text, and
tool schemas. Listed model metadata can supply a scoped input limit; unknown
models use a conservative planning threshold, explicitly not a known capacity.
Compatible reported input usage anchors later estimates. Context pressure first
prunes old successful tool bodies, then reduces older complete groups into
bounded structured task state while retaining a recent verbatim tail.

Compaction commits canonical-prefix and active-projection hashes with the new
summary. It is the only prefix-rewrite boundary: current guidance and task notes
replace old deltas, while full history remains intact. Attempt records capture
reductions and failures. One clear provider overflow can retry a materially
smaller request without replaying completed tools. Policy, schemas, instruction
state, and workspace authority are reconstructed outside model-generated task
notes; notes cannot authorize actions or assert current workspace truth.

Usage distinguishes reported, estimated, and unknown counts. Diagnostics show
categories and separate compaction/title requests without double-counting cached
input or inventing universal costs. OpenRouter's `cache_write_tokens` is parsed
only where documented. Native HTTPS `api.anthropic.com/v1/messages` requests for
Claude use automatic ephemeral caching (five-minute default); proxies and other
transports keep their existing contract. See
[Anthropic caching](https://platform.claude.com/docs/en/build-with-claude/prompt-caching)
and [OpenRouter usage](https://openrouter.ai/docs/guides/best-practices/prompt-caching).

New conversations enroll for one isolated title request after their first
successful turn: at most 2 KiB human input and 1 KiB completed answer, default
reasoning, no tools. A metadata marker and SQL compare-and-set preserve manual
names without a schema change. Title usage is separate and cannot anchor coding
context; canonical/active events do not change.

## App updates and release security

Settings manually checks the official latest published GitHub release and exact
`Rivet-v<version>.apk` asset. A separate credential-free HTTPS client streams up
to 200 MiB into private cache. AOSP apksig verifies integrity; Android package
metadata must match Rivet, the expected version name, a higher version code, and
the installed signer set. Only verified bytes reach Downloads (API 29+) or a
save picker (API 26–28). Owned partials/pending exports are cleaned on restart,
and the Activity ViewModel retains work across rotation. No polling or installer
is involved.

Release builds have no debug cleartext exception. Debug alone permits cleartext
mock-provider testing; neither variant replaces HTTPS certificate validation.
The public debug key is distinct from the permanent production signer. Release
CI requires secrets and an independently committed certificate pin, then
verifies identity before staging the public filename. See
[RELEASE_PROCESS.md](RELEASE_PROCESS.md).

## Testing and recovery

Provider I/O and filesystem work run off the UI thread. Cancellation signals
and closing descriptors interrupt reads where supported; document providers
can delay or ignore cancellation. Runtime/preflight errors stop or offer a
specific recovery action rather than inviting model retries around unavailable
state. Completed tool results remain authoritative even when model prose differs.

Physical mutation testing uses a disposable project with generated text, binary,
search, rename/move/delete, and normalization fixtures. Never adversarially test
destructive operations against a user's existing project.
