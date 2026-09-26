# Rivet

An Android-native AI coding harness: pick a provider, point it at a
project, and work with a coding agent entirely from your phone or
tablet — no desktop setup required.

**Status: 0.8.2 cleanup candidate.** The 0.8.1 debug RC passed on-device
acceptance, including an in-place upgrade, agent file and command work, Undo,
denial, and restart persistence. This cleanup build still needs independent
verification and a short physical regression test. Rivet is not publicly released.

## What works today

- Multi-provider chat: OpenAI, OpenRouter, Anthropic (native Messages
  API), Google Gemini (native generateContent), and any
  OpenAI-compatible endpoint with a custom base URL.
- API keys stored in Android-backed encrypted storage, never in
  plaintext.
- Model discovery where the provider supports listing, manual model entry
  everywhere else.
- Provider transport streams internally with stop control; Chat renders
  completed assistant messages rather than token-by-token text. Provider and
  model can be switched between messages.
- Multiple persistent conversations; completed history, provider
  configuration, and selected project restore after a restart.

- Project-folder selection from Chat, with contained file reads, search, edits,
  and exact-context patches.
- Structured tools for OpenAI-compatible, OpenRouter, Anthropic, and Gemini
  providers. File and command changes require approval.
- Approved project commands run through Rivet's on-device runtime. Git status
  and diffs are read-only; completed agent changes can be undone when safe.
- Bounded project instructions from `AGENTS.md`, durable conversation history,
  model switching, usage records, and automatic context reduction.

Rivet's native file tools are confined to the selected project folder. Approved
project commands run with Rivet's Android application UID; they are not a
security sandbox and may access app-private files available to that UID. Rivet
does not include stored API keys in the command environment. No broad storage
permission is requested. Files above 1 MiB and binary/non-UTF-8 files cannot be
edited. Some document providers reject writes or rename operations. External
project changes stop synchronization rather than being overwritten; Rivet may
need the project state resolved before work can continue. The contextual Undo
action is not restored after process restart. See [ARCHITECTURE.md](ARCHITECTURE.md)
for recovery and provider limits.

## Building

GitHub Actions is the primary build environment — push to `main` (or
open a PR) and CI runs tests, lint, and the debug APK build, uploading
the APK as an artifact. Local builds work with JDK 17 and Android SDK 35:

    ./gradlew assembleDebug

Releases are signed in CI from repository secrets; the full procedure
is in [RELEASE_PROCESS.md](RELEASE_PROCESS.md).

## Documentation

- [PROJECT_CONSTITUTION.md](PROJECT_CONSTITUTION.md) — durable project rules
- [ARCHITECTURE.md](ARCHITECTURE.md) — what exists today
- [MASTER_ROADMAP.md](MASTER_ROADMAP.md) — the eight phases
- [DECISIONS.md](DECISIONS.md) — architecture decision records
- [RELEASE_PROCESS.md](RELEASE_PROCESS.md) — versioning, signing, releases

## License

Apache-2.0 for Rivet's own code (see [LICENSE](LICENSE)). Third-party
components retain their licenses; the incorporated Termux-derived emulator
source is attributed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
Rivet is not affiliated with Termux.
