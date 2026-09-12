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

