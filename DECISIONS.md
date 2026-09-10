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
