# Rivet Decisions

Architecture decision records. Concise by policy; trivial choices are not
recorded.

## D1 — Application ID `com.kaiser.rivet` is permanent

What: the ID under which Rivet is forever published on Android.
Why: changing it orphans every installed copy.
Change trigger: none. This decision is not reversible.

## D2 — Release signing certificate is permanent from first signed release

What: every release APK is signed by the same key, held only in GitHub
Secrets.
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

What: one activity hosting a Compose shell; navigation is tab state in
`rememberSaveable`.
Why: the app is a single-surface tool; framework navigation and DI would
add machinery the current code does not need.

## D6 — Hand-drawn vector icons, no icon library

What: the four navigation glyphs plus settings are hand-authored
`res/drawable` vectors.
Why: the old `material-icons-core` artifacts publish as empty stubs, and
an icon dependency for five glyphs is not worth its weight.

## D7 — Dark-only shell

What: one dark color scheme; no light variant.
Why: a coding tool with a designed-dark interface; a light theme is only
on the table if a real request exists.

## D8 — Speculative abstraction is banned

What: no interfaces with one implementation, no future-proofing packages.
Why: the codebase grows by feature, and structure that exists before the
feature it serves is the primary decay vector for a repository like this.
Change trigger: a second real implementation demanding a seam.

## D9 — Pinned debug keystore committed, release key never committed

What: `debug.keystore` (public android/android credentials) is in the
repository; every other key material is gitignored and CI-only.
Why: consecutive CI debug APKs update in place instead of demanding
uninstall; release trust must live only in GitHub Secrets.

## D10 — Apache-2.0 for Rivet's own code

What: Rivet-authored code is Apache-2.0.
Why: permissive and adequate for Phase 1 scope. Future Termux-derived
runtime components bring their own (GPL) obligations and require a
dedicated review before incorporation; no combined-work licensing claims
are made now.

## D11 — Versioning: explicit `versionCode`/`versionName`, no automation

What: humans bump `versionCode`/`versionName` in `app/build.gradle.kts`
per release; rules in RELEASE_PROCESS.md.
Why: simple, auditable, impossible to get wrong silently.

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

What: provider configs, active provider id, and the one chat conversation
live in three Preference DataStores with stable string keys.
Why: small data, reactive, schema-evolution via JSON list decode with
`ignoreUnknownKeys`; a database is unjustified for this size.
Change trigger: multi-session chat (Phase 4+) demanding relational storage.

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

What: activity-scoped workspace state, generation-checked reads/searches,
serialized mutations, and persisted URI/directory/file paths only. Search has explicit
file/entry/byte/result limits and cancellation. Test-only Robolectric runs a
small disposable DocumentsProvider to exercise the native contract.
Why: rotation keeps edits and jobs without saving whole project contents;
process death does not pretend to preserve drafts or active asynchronous work.
Phase 4 reuses these operations through the separately approved agent boundary.

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

What: DataStore holds one provider-neutral transcript plus an active-turn marker.
Legacy text chat imports once. Completed assistant/tool events persist; pending
approvals and partial tool calls do not. Restart reports interruption and never
resumes work.
Why: process death cannot safely reconstruct network or mutation authority, while
completed context and existing user data must survive an in-place update.

## D25 — Private POSIX mirror with SAF as external authority

What: shell processes use an app-private worktree. Binary streaming copies
and a content-hash baseline connect it to the selected SAF tree. A stale SAF
baseline stops sync before mutation; partial failures preserve the mirror.
Why: SAF documents are not POSIX paths, and shell changes must not silently
overwrite external edits or disappear on process death.

## D26 — System shell and pinned terminal libraries first

What: Phase 5 uses `/system/bin/sh` and selected Android system utilities.
The Apache-exception Termux terminal libraries are pinned and adapted for PTY
display. No writable app-data executable or Termux package is required.
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
