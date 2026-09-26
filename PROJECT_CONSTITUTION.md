# Rivet Project Constitution

Durable rules for the Rivet repository. Every contribution is measured
against this document.

## Scope

Rivet is an Android-native AI coding harness: provider-agnostic model access,
a coding agent with tool execution, a project workspace, an embedded runtime,
and optional read-only Git inspection — all on-device, with no companion desktop
setup required by the user.

Out of scope: analytics, accounts, cloud relays of user code, and any
feature that does not serve the on-device coding workflow.

## Architecture

- Direct code over speculative abstraction. An interface needs a current
  consumer or a concrete platform/test boundary; no factory for one product
  or layer a current feature does not use.
- No global mutable state.
- Pure logic stays separable from Android framework types where it
  improves testability; the test suite decides where that boundary pays.
- No `Utils`/`Helper`/`Manager` dumping grounds; names state the actual
  responsibility.
- No dependency-injection framework. Constructor injection, applied where
  dependencies actually exist.
- Packages grow with features. Never create empty directories for future
  work.

## Dependencies

- AndroidX and platform APIs first.
- A dependency earns its place by an immediate use in shipped code, not by
  anticipation.
- No two libraries solving the same problem.
- Removal is on the table whenever a dependency stops carrying its weight.

## Repository quality

- Comments carry constraints, invariants, platform quirks, or
  security-sensitive reasoning — never narration of obvious code.
- No prompt text, conversation fragments, or tool narration in the tree.
- No placeholder systems that look functional.
- No TODO without a concrete, intentionally-deferred task.
- Small diffs, honest commit messages, conventional style.

## Security

- API keys and secrets are never committed, never logged, and never stored
  in plain-text preferences.
- Generate the permanent production signing key once on a trusted machine.
  Retain a safe offline recovery backup and provide an encoded CI copy and
  signing passwords through GitHub Secrets. Pin the certificate fingerprint
  independently before public release.
- No telemetry, no analytics, no crash reporting to third parties by
  default.
- Third-party code keeps its own license; obligations are followed, not
  bypassed.

## Compatibility

- `applicationId` `com.kaiser.rivet` is permanent.
- The release signing certificate is permanent from the first signed
  release onward.
- Any APK that should update an existing installation must carry the same
  application ID, the same certificate, and a higher `versionCode`.
- minSdk moves only with demonstrated need, never casually.

## Licensing

Rivet's own code is Apache-2.0. Incorporated Termux-derived emulator code
retains its applicable license and notices; future third-party runtime additions
require license review before incorporation. Rivet is not affiliated with Termux.
