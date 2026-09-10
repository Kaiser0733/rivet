# Rivet

An Android-native AI coding harness: pick a provider, point it at a
project, and work with a coding agent entirely from your phone or
tablet — no desktop setup required.

**Status: early development.** Current phase: 1 of 8 (Foundation) — see
[MASTER_ROADMAP.md](MASTER_ROADMAP.md).

## What works today

The application shell only: it installs and launches, shows the Chat /
Files / Changes / Terminal navigation frame with honest empty states,
adapts between phone and tablet layouts, and reports its version in
Settings. There is no provider, agent, runtime, or project
functionality yet; those arrive in later phases.

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
