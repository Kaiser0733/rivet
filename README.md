# Rivet

An Android-native AI coding harness: pick a provider, point it at a
project, and work with a coding agent entirely from your phone or
tablet — no desktop setup required.

**Status: early development.** Current phase: 4 of 8 (Agent Loop) —
see [MASTER_ROADMAP.md](MASTER_ROADMAP.md).

## What works today

- Multi-provider chat: OpenAI, OpenRouter, Anthropic (native Messages
  API), Google Gemini (native generateContent), and any
  OpenAI-compatible endpoint with a custom base URL.
- API keys stored in Android-backed encrypted storage, never in
  plaintext.
- Model discovery where the provider supports listing, manual model entry
  everywhere else.
- Live streaming responses with stop control; provider and model
  switchable between messages.
- One persistent conversation; completed chat history, provider
  configuration, and selection restore from app-private storage after a
  restart.

- Native project-folder selection through Android's Storage Access Framework;
  nested browsing, UTF-8 editing, create/rename/delete, and provider-native moves.
- SHA-256-checked saves, exact-context patches, and bounded literal project search.
- Native structured workspace tools across OpenAI-compatible, OpenRouter,
  Anthropic, and Gemini transports.
- Automatic bounded reads and explicit approval for every file mutation, with
  stop control and workspace binding for active turns.
- Provider-neutral agent history, including tool calls and results, with one-time
  migration of existing chat history.

Terminal/runtime and Git integration are not implemented. Workspace access stays inside the selected document tree;
no broad storage permission is requested. Files above 1 MiB and binary/non-UTF-8
files cannot be edited. Read-only/cloud providers may reject mutations. Save
conflicts retain the draft; SAF writes are not universally atomic. Unsaved
drafts survive rotation, not process death. See ARCHITECTURE.md for limits.

## Building

GitHub Actions is the primary build environment — push to `main` (or
open a PR) and CI runs tests, lint, and the debug APK build, uploading
the APK as an artifact. Local builds work with any JDK 17 + Android SDK
35 setup:

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
components retain their licenses. Rivet is not affiliated with Termux;
any future runtime integration undergoes dedicated license review first.
