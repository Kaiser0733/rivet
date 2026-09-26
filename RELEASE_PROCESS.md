# Rivet Release Process

How a Rivet APK gets built, signed, and installed over a previous
version. Version rules first; they are short and absolute.

## Version rules

- `versionCode` is a plain integer, bumped by hand for every release.
  It must always increase.
- `versionName` is `MAJOR.MINOR.PATCH` (`0.1.0` at Phase 1 start),
  bumped by hand on the same commit as the `versionCode` bump.
- Both live in `app/build.gradle.kts`. Nothing computes them.

## Updating an installed copy

An APK installs over an existing Rivet only when, compared to the
installed build:

1. `applicationId` is identical: `com.kaiser.rivet`.
2. The signing certificate is identical (same release keystore).
3. `versionCode` is higher.

Fail any one and Android refuses the install (or requires an uninstall,
which loses user data). APK verification compares the built package, version,
and SDK levels with the current Gradle declarations; an optional release tag
must match the built version name. It does not compare `versionCode` with a
previous release. Check that increase against the installed build before
publishing. The signer is compared with the build keystore and a separately
committed production certificate fingerprint.

The 0.8.1 debug RC passed physical in-place upgrade and core Chat/agent
acceptance. The 0.8.2 cleanup candidate needs independent verification and
a short on-device regression test. Debug acceptance does not establish
production-signing readiness or constitute a public release.

## Required GitHub secrets

| Secret | Contents |
|---|---|
| `RIVET_KEYSTORE_BASE64` | The release keystore, base64 of the file |
| `RIVET_KEYSTORE_PASSWORD` | Keystore store password |
| `RIVET_KEY_ALIAS` | Key alias inside that keystore |
| `RIVET_KEY_PASSWORD` | Password of that key |

## One-time keystore setup (documented, never automated)

Generate the production key once, on a trusted machine, and keep a safe
offline recovery backup. Losing it means every future release can no
longer update installed copies. GitHub Secrets hold an encoded CI copy and
the signing passwords, not the only permanent copy.

    keytool -genkeypair -keystore rivet-release.keystore \
        -alias rivet -keyalg RSA -keysize 4096 -validity 10000

    # do not commit this file
    base64 -w0 rivet-release.keystore > rivet-release.keystore.b64

Add the four secrets in GitHub: Settings → Secrets and variables →
Actions. Store the b64 contents in `RIVET_KEYSTORE_BASE64`.

Commit the verified production certificate SHA-256 as the sole lowercase
hexadecimal line in `release/production-signer.sha256`. Establish that
fingerprint from the permanent certificate independently of the CI secret.
Until this file exists with the real fingerprint, production releases are
intentionally unavailable. Do not use the debug certificate as its pin.

## Workflows

`.github/workflows/android-build.yml` — every push to `main` and every
PR: tests, lint, debug APK (signed with the committed public debug key),
APK verification, artifact upload.

`.github/workflows/release.yml` — manual trigger. Refuses to run unless
all four release secrets exist, decodes the keystore, builds and signs
the release APK, verifies signer against the committed pin and identity,
uploads the APK as an
artifact, and optionally opens a draft GitHub Release with the APK
attached when a tag input exactly matches `v` plus the APK version name.

## Releasing, in order

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`; commit
   (e.g. `build: release 0.2.0, versionCode 2`).
2. In GitHub on any device: Actions → Release → Run workflow. Leave the
   tag input empty for an APK artifact only, or pass a tag such as
   `v0.2.0` to also create a draft release.
3. Download `rivet-release-apk` from the run page; install over the
   previous build.
4. Publish the draft release from the Releases page after checking it.

## What must never change between releases

- The application ID.
- The release certificate and keystore.
- The four secret names (CI depends on them).

What must change: `versionCode` (and normally `versionName`).

## Signing hygiene

- During CI signing, the secret is decoded only on the runner and the
  temporary file is deleted in an `if: always()` cleanup. The trusted-machine
  original and offline recovery backup are separate from that CI file.
- Passwords travel as env vars read by Gradle at configuration time;
  they are never written into the repository or logs.
- With secrets missing, the release workflow fails early and the debug
  workflow's release-type output stays unsigned — there is no silent
  fallback to the debug key.
