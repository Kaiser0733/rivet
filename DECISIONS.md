# Rivet Decisions

Architecture decision records. Concise by policy; trivial choices are not
recorded.

## D1 — Application ID `com.kaiser.rivet` is permanent

What: the ID under which Rivet is forever published on Android.
Why: changing it orphans every installed copy.
Change trigger: none. This decision is not reversible.

## D2 — Release signing certificate is permanent from first signed release

What: every release APK uses the same permanent certificate. Generate its
key once on a trusted machine, retain a safe offline backup, provide an
encoded CI copy through GitHub Secrets, and pin the certificate fingerprint
independently before public release.
Why: Android updates an installation only when application ID and signer
certificate both match.
Change trigger: a compromised key, handled as an explicit migration event
documented in RELEASE_PROCESS.md — never silently.

## D3 — GitHub Actions is the primary build environment

What: CI builds every APK, including release candidates; local Android
builds are not part of the workflow.
Why: the developer works from an Android tablet; CI guarantees identical
builds and keeps signing material off local devices.

## D4 — Kotlin 2.1.10, Compose via BOM 2025.03.00, AGP 8.9.2, Gradle 8.13, JDK 17, compileSdk/targetSdk 35, minSdk 26

What: the toolchain matrix, pinned at the root build file.
Why: a mutually compatible, currently maintained set; minSdk 26 covers
adaptive icons and current devices without legacy work.
Change trigger: a security fix or a required platform feature, with the
whole matrix re-validated together — never piecemeal dependency bumps.

## D5 — Compose, plain single-activity shell, no DI framework

What: one activity hosting a Compose shell; Chat/Settings navigation is state in
`rememberSaveable`.
Why: the app is a single-surface tool; framework navigation and DI would
add machinery the current code does not need.

## D6 — Hand-drawn vector icons, no icon library

What: UI icons use hand-authored `res/drawable` vectors. The original four
navigation glyphs were reduced with the Chat-first surface under D30.
Why: the old `material-icons-core` artifacts publish as empty stubs, and
an icon dependency for five glyphs is not worth its weight.

## D7 — Dark-only shell

What: one dark color scheme; no light variant.
Why: a coding tool with a designed-dark interface; a light theme is only
on the table if a real request exists.

## D8 — Speculative abstraction is banned

What: no speculative interface or package without a present consumer or
testable platform boundary.
Why: the codebase grows by feature, and structure that exists before the
feature it serves is the primary decay vector for a repository like this.
Change trigger: a second real implementation demanding a seam.

## D9 — Pinned debug keystore committed, release key never committed

What: `debug.keystore` (public android/android credentials) is in the
repository; production key material is never committed. An offline recovery
backup and an encoded CI-secret copy are retained separately.
Why: consecutive CI debug APKs update in place instead of demanding
uninstall; release trust depends on the independently pinned production signer.

## D10 — Apache-2.0 for Rivet's own code

What: Rivet-authored code is Apache-2.0.
Why: Rivet's original code uses a permissive license. Incorporated
Termux-derived emulator code retains its upstream Apache-2.0 exception and
component notices; later third-party additions require a separate review.

## D11 — Versioning: explicit `versionCode`/`versionName`, no automation

What: humans bump `versionCode`/`versionName` in `app/build.gradle.kts`
per release; rules in RELEASE_PROCESS.md.
Why: simple and auditable when the APK identity and upgrade path are verified.

## D12 — No icon-font or emoji glyphs in the UI

What: UI iconography is vector drawables.
Why: consistency and a deliberate visual identity instead of a generic
icon look.

## D13 — OkHttp 4 single networking stack, no retries

What: one OkHttp client serves every provider; no per-provider HTTP code.
Why: streaming, cancellation, and timeouts are solved once; SSE parsing is
the only per-transport variation.
Change trigger: a provider requiring a genuinely different transport (e.g.
WebSocket) — still shared infrastructure, not a parallel client stack.
No automatic retry of user messages: one transparent failure beats hidden
duplicate sends.

## D14 — API keys encrypted with Android Keystore, never in DataStore

What: `SecretStore` generates a non-exportable AES-256 key directly in
AndroidKeyStore and uses AES/GCM/NoPadding. App-private preferences contain
only versioned IV+ciphertext records keyed by provider id; the id is also
authenticated as associated data.
Why: platform APIs provide the required boundary without the deprecated
security-crypto wrappers. A malformed record affects only that credential;
unrelated failures never wipe the store.
Migration: no production release used the former EncryptedSharedPreferences
format. Development installs must enter credentials once into the new store.

## D15 — Reasoning: capability-gated per provider and model

What: custom OpenAI-compatible providers and Gemini expose no reasoning
control and receive no optional reasoning field. Known OpenAI reasoning-model
families use `reasoning_effort`; OpenRouter retains its documented shorthand,
including `xhigh`. A strict current Anthropic allowlist uses manual
`thinking.budget_tokens` for documented 4.5 aliases/snapshots and adaptive
thinking with `output_config.effort` for documented 4.6+ aliases; retired,
unknown, or merely family-shaped ids receive neither.
Why: a baseline chat request must work on providers that reject unknown
fields. Unknown capability always degrades to provider-default behavior.
Change trigger: model metadata is persisted reliably enough to replace the
conservative model-family checks.

## D16 — Send-time provider/model snapshot

What: the active request keeps the provider and model captured when Send
was pressed; selection changes apply to the next message.
Why: mid-stream redirects corrupt an active request and double-bill;
documented behavior beats implicit behavior.
Change trigger: none expected.

## D17 — DataStore Preferences for non-secret persistence

Historical choice: provider configs, active provider id, and a single chat
conversation used Preference DataStores with stable string keys.
Why: those early values were small and reactive. Superseded for conversations
by D29: current session history is SQLite, while provider configuration stays
in DataStore. The old chat keys remain readable for one-time migration.

## D18 — SAF tree as the workspace boundary

What: native DocumentsContract operations under a persistently granted tree URI;
no raw filesystem paths, shell commands, or broad storage permissions.
Why: scoped storage and read-only/cloud provider compatibility. Relative paths
resolve one directory at a time, and provider metadata carries capabilities.
Change trigger: a new Android storage contract, not a runtime shortcut.

## D19 — Bounded UTF-8 snapshots and optimistic conflict detection

What: 1 MiB editable-file limit, strict UTF-8 decoding, original-byte SHA-256
snapshots. Saves recheck the hash and verify the resulting bytes. Exact patches
validate sequential unique matches entirely in memory before saving.
Why: bounded Android memory use and explicit external-change conflicts. SAF
cannot guarantee atomic writes or lock out external writers; provider failure
may leave partial content. Drafts remain in memory on save errors.

## D20 — Native moves only, capability-gated mutations

What: create/delete/rename/move use platform document APIs; no copy/delete move
fallback. Returned identities are resolved again; the root is immutable.
Why: provider rejection is safer than a partial fallback that loses source data.
Change trigger: a demonstrated need for a separately verified, recoverable copy
protocol, with explicit directory semantics.

## D21 — ViewModel drafts and bounded unindexed search

Historical Phase 3 choice: the Files ViewModel kept drafts and jobs across
rotation, generation-checked reads, serialized mutations, and persisted only
URI/path identity. The Files presentation was retired in 0.8.2. Bounded SAF
search and its DocumentsProvider tests remain because the agent's read-only
search tool uses that native workspace contract.

## D22 — Provider-neutral transcripts, native provider tools

What: persisted agent messages contain neutral text, calls, IDs, and results.
OpenAI-compatible, Anthropic, and Gemini syntax stays inside their adapters;
opaque continuation state is secondary metadata for signed reasoning blocks.
Why: the loop has one execution model while each transport preserves its native
structured protocol. Assistant prose is never parsed or executed as a tool.

## D23 — SAF remains authoritative for agent tools

What: three read-only tools run automatically. Seven native file mutations and
each agent command require a fresh, one-shot approval. File tools retain hash,
patch, path, capability, root, and move rules from `SafWorkspace`. Turns bind
to the tree selected at Send.
Why: model arguments are untrusted. Approval and identity checks prevent an old
turn from changing a replacement workspace or replaying after process death.

## D24 — Durable completed events, interrupted turns do not resume

Historical storage: DataStore held one provider-neutral transcript and an
active-turn marker, importing older text chat once. D29 superseded the storage
medium with SQLite. The durable rule remains: completed events survive;
pending approvals and partial calls do not resume after restart.
Why: process death cannot safely reconstruct network or mutation authority, while
completed context and existing user data must survive an in-place update.

## D25 — Private POSIX mirror with SAF as external authority

What: shell processes use an app-private worktree. Binary streaming copies
and a content-hash baseline connect it to the selected SAF tree. A stale SAF
baseline stops sync before mutation; retry may resume entries already matching
the mirror target, while third-party states preserve both sides and block writes.
Why: SAF documents are not POSIX paths, and shell changes must not silently
overwrite external edits or disappear on process death.

## D26 — System shell and pinned terminal components first

Historical Phase 5 choice: `/system/bin/sh` and selected Android utilities,
with pinned Apache-exception Termux terminal components for a PTY prototype.
After D30, interactive PTY presentation and `:terminal-view` were retired;
`:terminal-emulator` still packages Rivet's native `command.c` launcher for
the active agent command tool. No writable app-data executable or Termux
package is required.
Why: target SDK 35 blocks direct execution of writable app data. Packaged
executables need their own linker and shebang compatibility work after the
runtime and sync boundary is validated on a device.

## D27 — JGit core for read-only repository inspection

What: inspect an explicit root `.git` directory in the private SAF mirror
with JGit core. Gitfile links, which may point outside the workspace, are not
followed. Rivet does not stage, commit, reset, or sync repository metadata for
inspection.
Why: one Java library supplies real Git status and bounded diffs without
requiring an external Termux install or packaging native Git executables.

## D28 — Private pre-turn checkpoints

What: stream one pre-change archive per mutating agent turn into app-private
storage, excluding `.git`; require a matching post-change manifest for Undo.
Why: recovery must work without a user Git repository and must not overwrite
later user or external changes. Checkpoint failure blocks the mutation.

## D29 — SQLite sessions and separate active context

What: import the bounded DataStore agent transcript once into SQLite event
rows, leaving the source intact. Full rows are durable; only active model
context is compacted. Provider/model selection remains independent of a
workspace-bound session.
Why: a single Preferences value cannot hold a long coding conversation.
Security policy, approvals, and project guidance are rebuilt outside summaries.

## D30 — Chat-first normal surface

What: Chat and Settings are the only normal destinations. Project selection,
approvals, activity, changed files, and Undo appear in Chat when relevant.
SAF tools, the command runtime, read-only Git inspection, checkpoints, and
synchronization remain available to the agent without technical navigation.
The former Files, Changes, and interactive Terminal presentation was retired
for the 0.8.2 cleanup candidate.
Why: local coding work should start with a project and a request; users should
not need to operate the engine's development and recovery screens.
