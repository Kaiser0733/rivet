# Starters gallery

Branch `starters` starts at `batch/surface-fix` commit `0107e6b836c4f1123bacb6e591c6f278ef987d83`. The checked-out branch already included Run replays.

## Changes by component

| Component | Commit | Files and behavior |
| --- | --- | --- |
| B1 · Bundled projects | `11337335e70b9d12182a94e4c1ba0386e9c3e43a` | `starters/{voxel-world,open-city,my-ai,idea-lab}/` contains the editable templates and four READMEs. `scripts/bundle_starters.py` builds the four archives and updates `app/src/main/assets/starters/starters.json`. `starter/StarterCatalog.kt` validates schema, IDs, asset paths, copy and byte ceilings. `StarterCatalogTest` checks the validations and opens the bundled archives. The archives total 11,316 bytes. |
| B2 · Static channel | `6e005cf22898eebf1b6467d11ba7e475d05c4355` | `starter/StarterChannel.kt` fetches the optional raw GitHub `starters/channel.json` with the existing OkHttp client, merges overrides in place, appends new IDs and filters by Android version code. It exposes remote rows after downloading and verifying the compatible archives in `cacheDir/starter-channel`. `StarterCatalog.kt` accepts the remote metadata. `StarterChannelTest` covers precedence, compatibility, SHA-256, corruption, offline fallback, schema and duplicate IDs with an injected transport. |
| B3 · Gallery | `af846b96afbb497e2bfa3e5a66a898b4718f4ee4` | `ui/starter/StarterGallery.kt` shows name, blurb and size in a bottom sheet. `ui/chat/ChatScreen.kt` adds the chip beside the existing empty-state project choice and offers it in the no-provider empty state as well. `StarterGalleryContent.kt` and `StarterGalleryContentTest` cover stable order, copy, sizes and fallback if a cached archive disappears. The app reads its installed version through `PackageManager` because this build does not generate `BuildConfig`. |
| B4 · Install and briefing | `84edfe4ec456ebae576dffcc3327d398845587b2` | `starter/StarterInstall.kt` validates every entry and the expanded size before copying. `WorkspaceSelection.kt`, `SafWorkspace.kt`, `ChatViewModel.kt` and `ChatScreen.kt` route a starter through the same SAF launcher and keep the previous selection if preparation fails. The installer checks that the folder is empty, then creates fresh entries and verifies writes against the created SAF document IDs. `CodingSessions.kt` and `InternalContext.kt` store the brief as a project-bound context row at session creation, retain it through compaction and project it onto the model wire. The opening reply runs without tools after a provider becomes available. `StarterInstallTest`, `StarterChannelTest` and `CodingSessionsTest` cover path refusal, non-empty refusal, brief visibility, persisted context and archive validation. The channel uses the same archive validation before exposing downloads. |
| B5 · No-key note | `5ce69997782d6d59cd0290f6f1a68412f09fe9c9` | `ChatScreen.kt`, `StarterGallery.kt` and `StarterGalleryContent.kt` show the requested local-model footnote when the configured providers have no stored API key. The gallery reads the existing SecretStore boolean. `StarterGalleryContentTest` checks the exact copy and conditions. |

## Verification

- **17 isolated Kotlin/JUnit tests passed** across the catalog, channel, archive installer, context wire and gallery copy. The harness used the production starter code, context projection and activity projection with stubs for unrelated agent references and the default HTTP client. The channel tests made no live requests.
- **Parent regressions reproduced.** The zip-slip channel test failed on the B4 parent because it exposed the unsafe archive after matching its hash. The B5 footnote test failed against the B4 implementation because the selector did not exist yet.
- **36 repo script tests passed** with `python3 -m unittest discover -s scripts -p 'test_*.py'`.
- **Isolated Android API compilation passed** for the workspace package, including the modified SAF selection and writer calls.
- **Template smoke checks passed.** Python verified the archive members, CRCs, indexed lengths and equality to the editable files. Node syntax checks passed for all four scripts. A Node smoke harness with simulated DOM and Canvas exercised voxel movement, look, break and place, city driving and collision, the offline AI reply and the idea-lab waitlist stub.
- `git diff --check` passed. The manifest, app build configuration and updater have an empty diff against the branch base.

## Honest gaps

- The shared phone checkout was inaccessible in this session, so work used `/workspace/rivet`. The fetched repository has no tracked root `AGENTS.md`. The task brief governed the work.
- Gradle stopped during `:terminal-emulator` configuration because this checkout has no Android SDK configured. The Android app build and the Robolectric session reload test remain for Hermes' gate.
- Device checks remain for the gallery layout, chip placement, SAF folder selection and copying, the first model reply after installation, and playing all four templates in a phone browser. A SAF provider failure partway through copying can leave newly created files in the chosen folder. Rivet leaves the prior project selected and shows a message asking the user to check the folder before trying again.
- The live starter channel endpoint and a configured live endpoint in the AI template were not exercised. The channel route can be published independently at `app/src/main/assets/starters/channel.json` on `main`. The installed gallery falls back to the four bundled templates when that route is unavailable.
