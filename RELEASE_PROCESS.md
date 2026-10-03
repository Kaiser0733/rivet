# Release process

Verify the release candidate's `versionName` and `versionCode` in
`app/build.gradle.kts` before building. Production releases require all four
GitHub signing secrets and the independently committed signer pin. The workflow
fails closed when either is missing; debug acceptance is not a public release.

## Identity and versions

`app/build.gradle.kts` declares `versionName` (`MAJOR.MINOR.PATCH`) and integer
`versionCode`. Change them by hand. Every new distributed build must increase
`versionCode`; candidates can keep the same version name.

The application ID, **`com.kaiser.rivet`**, is permanent. Android upgrades require
the same application ID and signer; public updates must also have a higher
version code. A production key differs from the committed public debug key, so
**the first production APK cannot upgrade a debug installation**. Test it on a
clean device or an installation already signed with that production key.

Verification compares the built package, version, SDK levels, permissions, and
signer with the declared contract. It does not check version-code progression
against a previous release; the person publishing must check that separately.

## One-time production-key setup

The human owner generates the permanent key **once on a trusted environment**,
not in CI, the repository, or an agent environment. Use strong unique store and
key passwords. With keytool's default PKCS12 format, the key password is the
store password; use that value for both password secrets. Back up the keystore
offline in a safe recovery location before
uploading any CI copy. Losing the key prevents future in-place updates.

```sh
keytool -genkeypair -keystore rivet-release.keystore \
    -alias rivet -keyalg RSA -keysize 4096 -validity 10000
keytool -exportcert -rfc -keystore rivet-release.keystore \
    -alias rivet -file rivet-release-cert.pem
openssl x509 -in rivet-release-cert.pem -noout -fingerprint -sha256
keytool -list -v -keystore rivet-release.keystore -alias rivet
```

Check that the two certificate fingerprints agree. Commit **only the verified
public SHA-256**, as one lowercase 64-digit hexadecimal line without colons,
in `release/production-signer.sha256`. Create its parent directory when adding
the real pin; no placeholder is needed. Never substitute the debug fingerprint.

Add these GitHub Actions repository secrets:

| Secret | Contents |
|---|---|
| `RIVET_KEYSTORE_BASE64` | Base64-encoded production keystore |
| `RIVET_KEYSTORE_PASSWORD` | Store password |
| `RIVET_KEY_ALIAS` | Alias of the production key |
| `RIVET_KEY_PASSWORD` | Key password |

Encode the keystore locally, for example with `base64 -w0` where supported.
Never commit the keystore, encoded copy, passwords, or private key. GitHub
Secrets contain a CI copy, not the only backup. Retain this exact key for every
public version; a compromise needs a separately planned migration.

## Build workflows

- **Android Build** (`.github/workflows/android-build.yml`) runs on main pushes,
  pull requests, and manual dispatch. It runs Python checks, unit tests, lint,
  debug assembly, release manifest/resource isolation checks, process-boundary
  checks, and APK identity/signature verification. Only then does it stage
  `Rivet-<version>-code<code>-debug.apk` in artifact `rivet-debug-apk`.
- **Release** (`.github/workflows/release.yml`) is manual. It requires all four
  secrets and `release/production-signer.sha256`, decodes a temporary keystore,
  builds the signed release, and verifies it against both that keystore and
  the independent pin. The temporary keystore is removed with `if: always()`.
  Missing signing configuration never falls back to the debug key.

Verification reads canonical Gradle outputs (`app-debug.apk` / `app-release.apk`)
before staging friendly filenames. The public artifact is **`rivet-release-apk`**
containing **`Rivet-v<versionName>.apk`**.
An empty `release_tag` builds an artifact only. A nonempty tag must match
`v<versionName>` and creates a **draft**, never a published release, targeting
the workflow's commit. Checkout does not retain credentials; only the Release
workflow has repository write permission for draft creation.

## Verify before publishing

1. Merge reviewed changes with successful exact-commit Android Build CI.
2. Complete the human key setup and review the committed public pin.
3. Open Actions → Release → Run workflow on the intended main commit. Leave
   `release_tag` empty for an artifact-only build; do not publish yet.
4. Download `rivet-release-apk`. Record the APK SHA-256, package, version name,
   version code, production certificate SHA-256, and requested permissions.
   Verify those independently against the pin and Gradle declarations.
5. Install on a clean device or the same production-signed baseline. Check
   provider/project setup, a disposable edit, command approval, Undo, and
   restart. Keep important project data backed up.
6. When ready, run Release with `release_tag=v<versionName>` to create the draft.
   Reverify the attached APK; a second build is not assumed byte-identical.
   Publish the draft only after those checks and the release notes are reviewed.

## Update contract

Settings → Check for updates queries only the official `Kaiser0733/rivet`
latest published normal release, excluding drafts and prereleases. The asset
must be named exactly **`Rivet-v<version>.apk`**, and the version name must be
newer than the installed version.

The credential-free HTTPS download is verified before export: valid APK
signature, exact package, expected version name, higher version code, and the
installed signer set. A debug installation rejects production-signed updates.
Verified files go to Downloads on API 29+, or the user save picker on API 26–28.
Rivet neither polls for updates nor installs APKs. Android and the file manager
handle installation; their security checks remain in place.
