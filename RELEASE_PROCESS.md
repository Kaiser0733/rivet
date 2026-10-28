# Release process

How a public Rivet build gets made. Short version: CI builds it, a human
checks it on a real phone, then it ships.

First, check `versionName` and `versionCode` in `app/build.gradle.kts`.
A public release needs all four signing secrets and the signer pin in the
repo. If either is missing, the workflow stops. A debug build is never a
public release.

## Identity and versions

- `versionName` looks like `MAJOR.MINOR.PATCH`. `versionCode` is a plain
  number. Both are set by hand in `app/build.gradle.kts`.
- Every new build that goes out must raise `versionCode`. Test candidates
  can keep the same version name.
- The app ID, **`com.kaiser.rivet`**, never changes. Updates need the same
  app ID, the same signer, and a higher version code.
- The production key is not the debug key. So the first production APK
  can't update a debug install. Test on a clean phone, or on one already
  running a production build.

The build check compares package, version, SDK levels, permissions, and
signer against what's declared. It does not check that the version code
went up since the last release. The person publishing checks that.

## One-time production-key setup

The owner makes the key **once**, on a trusted machine. Not in CI, not in
the repo, not in an agent session. Strong passwords, kept offline.

```sh
keytool -genkeypair -keystore rivet-release.keystore \
    -alias rivet -keyalg RSA -keysize 4096 -validity 10000
keytool -exportcert -rfc -keystore rivet-release.keystore \
    -alias rivet -file rivet-release-cert.pem
openssl x509 -in rivet-release-cert.pem -noout -fingerprint -sha256
keytool -list -v -keystore rivet-release.keystore -alias rivet
```

With keytool's default PKCS12 format, the key password is the store
password. Use that value for both password secrets.

Back up the keystore somewhere safe and offline **before** uploading a CI
copy. Lose this key and no future update can install over the old app.

Check the two certificate fingerprints match. Then commit **only** the
public SHA-256 — one lowercase 64-digit hex line, no colons — in
`release/production-signer.sha256`. Never use the debug fingerprint here.

Add these GitHub Actions secrets:

| Secret | Contents |
|---|---|
| `RIVET_KEYSTORE_BASE64` | Base64-encoded production keystore |
| `RIVET_KEYSTORE_PASSWORD` | Store password |
| `RIVET_KEY_ALIAS` | Alias of the production key |
| `RIVET_KEY_PASSWORD` | Key password |

Encode the keystore locally, e.g. `base64 -w0` where supported. Never
commit the keystore, an encoded copy, or any password. The GitHub copy is
a CI copy, not the only backup. If the key ever leaks, that's a planned
migration, not a quick swap.

## The two workflows

- **Android Build** (`.github/workflows/android-build.yml`) runs on pushes
  to main, pull requests, and by hand. It runs the Python checks, unit
  tests, lint, a debug build, and APK identity and signature checks. Only
  then does it stage `Rivet-<version>-code<code>-debug.apk` in the
  `rivet-debug-apk` artifact.
- **Release** (`.github/workflows/release.yml`) runs by hand only. It needs
  the four secrets and the pin, decodes a temporary keystore, builds the
  signed release, and checks it against both the keystore and the pin. The
  temporary keystore is deleted after, every time (`if: always()`). If the
  signing setup is missing, it stops — it never falls back to the debug key.

Both workflows read the plain Gradle outputs (`app-debug.apk` /
`app-release.apk`) first, then give the artifact its friendly name.
The public artifact is **`rivet-release-apk`**, holding
**`Rivet-v<versionName>.apk`**.

Leave `release_tag` empty and you get the artifact only. Give a tag — it
must match `v<versionName>` — and you get a **draft** release on that
commit, never a published one. Only the Release workflow can write to the
repo, to make that draft.

## Before you publish

1. Merge only reviewed changes, with green CI on that exact commit.
2. Do the key setup above. Read the committed pin once more.
3. Actions → Release → Run workflow, on the right main commit.
   Leave `release_tag` empty. Don't publish yet.
4. Download `rivet-release-apk`. Write down the APK SHA-256, package,
   version name, version code, signer SHA-256, and permissions.
   Check them against the pin and the Gradle file yourself.
5. Install it — clean phone, or the production-signed one. Set up a
   provider and a project. Make a throwaway edit, approve a command,
   undo it, restart the app. Keep real project data backed up.
6. Run Release again with `release_tag=v<versionName>` to make the draft.
   A fresh build may not match the old one byte for byte, so check the
   attached APK again. Publish only after that check and a read of the
   release notes.

## How updates work

**Settings → Check for updates** looks at the latest published release of
`Kaiser0733/rivet` only. Drafts and prereleases don't count. The file must
be named exactly **`Rivet-v<version>.apk`**, and its version must be newer
than the one installed.

Before saving, Rivet checks the download: real APK signature, right
package, expected version name, higher version code, same signer as the
installed app. A debug install rejects production updates. Good files land
in Downloads (API 29+), or wherever you pick (API 26–28).

Rivet never checks in the background and never installs anything itself.
Android and your file manager do the installing, with their normal checks.
