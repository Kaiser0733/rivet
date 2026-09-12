# Rivet

An Android-native AI coding harness: pick a provider, point it at a
project, and work with a coding agent entirely from your phone or
tablet — no desktop setup required.

**Status: early development.** Current phase: 2 of 8 (Provider Engine) —
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

The coding agent, project files, terminal, and Git integration are not
implemented yet — those arrive in later phases.

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
