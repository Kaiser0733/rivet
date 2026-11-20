# Run replays

Branch `replays` starts at `batch/surface-fix` commit `fe04474c5898298c2de5ac23200c6b91ae98d578`.

## Changes by component

| Component | Commit | Changes and tests |
| --- | --- | --- |
| A1 · Story | `a37fe032fe09a8305b9ba7840cdab6c5e4b032f2` | `replay/ReplayStory.kt` turns one journal record and its visible transcript slice into prompt, work, preview and result cards. It uses `AgentActivityProjection.conversation` for collapsed work lines, falls back to journal steps, and applies the 12-beat, text and 30–60 second caps. `ReplayStoryTest` covers ordering, caps, missing screenshots, empty runs and failed/stopped copy. |
| A2 · Frames | `d75844b32b566a5232c424b8de347cb90dd40920` | `replay/ReplayFrames.kt` draws portrait cards with Rivet's dark theme palette and monospace labels. It letterboxes browser WebP and phone PNG screenshots, draws the optional mark last, and uses a plain placeholder if decoding fails. `ReplayFramesTest` covers dimensions, the footer, missing images and letterboxing under Robolectric. |
| A3 · MP4 | `1818d84b5c12f2df1c0529cec9b4b4592508ee07` | `replay/ReplayVideoEncoder.kt` checks AVC encoder capabilities, submits frames with explicit timestamps at 2 fps and tries 720×1280 if 1080×1920 cannot encode. The sheet offers the GIF fallback message on failure. `replay/ReplaySchedule.kt` and `ReplayScheduleTest` cover frame counts, timestamps and bounds through 30 fps. |
| A4 · GIF | `d5fdd215a87dbe7000aa18dedb2544184cdf1f7b` | `replay/GifWriter.kt` writes GIF89a with a deterministic global palette and LZW. Per-card delays limit the rate to 10 fps. Maximum size is 540×960 and 60 seconds. The 8,000,000-byte ceiling is enforced before writing to the destination. `GifWriterTest` checks the file boundaries, palette, LZW width changes and resets with a reference decoder, and refusal before write. |
| A5 · Export controls | `bc1b9f21ed9f5f47f59cd7517c64c627660122db` | `ui/chat/ChatScreen.kt` adds Export replay to the finished fold row and an action on turns with no tool work. `ui/chat/ReplayExportSheet.kt`, `replay/ReplayExportOptions.kt` and `replay/ReplayExporter.kt` provide the three choices and watermark feedback. `storage/ReplaySettingsStore.kt` and `ui/provider/SettingsScreen.kt` add the default-on toggle under Phone access. `chat/ChatViewModel.kt` and `storage/CodingSessions.kt` bind new terminal outcomes to durable opening events and resolve the selected transcript slice from the same query behind `recent()`. A JiTTest in `CodingSessionsTest` distinguishes repeated prompts across reopening. `ReplaySettingsStoreTest` covers persisted opt-out. `ReplayExportOptionsTest` and the legacy story test cover the pure defaults and fallback. |
| A6 · Save and share | `9e3543b2848473a7c1cd8cc97aaa05d2cc7a194b` | `ReplayExporter.kt`, `ReplayExportOptions.kt` and `ReplayExportSheet.kt` save MP4 to Movies/Rivet and GIF to Pictures/Rivet through pending MediaStore entries on API 29+. Older versions open sharing for both formats. The Share sheet choice shares the prepared format or starts with video. `runtime/PhoneIntents.kt` shares the same chooser code with the existing tool. `res/xml/shared_file_paths.xml` exposes only the new replay cache subdirectory to the existing FileProvider. This commit also finishes screenshot buffer ownership in `ReplayFrames.kt` so the decoded bitmap is recycled before the output bitmap is allocated. `ReplayExportOptionsTest` covers the API split and `ReplayShareTest` checks the SEND intent, MIME, URI and read grant. |

## Verification

- **14 isolated Kotlin/JUnit tests passed.** The parent slices were red for the new story, schedule, GIF and export option symbols, and for the missing gallery-routing policy before A6. The isolated harness compiled the production replay logic and the real activity projection, with stubs for unrelated agent context dependencies.
- **36 repository script tests passed** with `python3 -m unittest discover -s scripts -p 'test_*.py'`.
- **API 35 isolated Kotlin compile passed** for the replay package and the real phone intent implementation against Android and AndroidX API jars. This checked framework signatures for Canvas, BitmapFactory, EGL, MediaCodec, MediaMuxer, MediaStore and FileProvider.
- **Independent GIF decode passed.** Pillow decoded three 540×960 frames and confirmed the known palette colors, a 30-second timeline and a file below the byte ceiling.
- `git diff --check` passed. The manifest and app build configuration diff against the base is empty.

## Honest gaps

- Gradle stopped during `:terminal-emulator` configuration because this checkout has no Android SDK configured through `ANDROID_HOME` or `local.properties`. The Android app compilation and the Robolectric bitmap, DataStore, transcript and share tests still need Hermes' CI gate.
- Device checks remain for typography and screenshot rendering, real hardware AVC output and the 720p fallback, GIF playback in Android share targets, MediaStore gallery visibility, and chooser URI handoff on both sides of API 29.
- Historical turns without a recorded terminal outcome show that fact and preserve the last visible reply. Their journals lack a reliable turn link. The sheet operates on the visible transcript window and recovers a clipped opening prompt when it can find the preceding user event. Export actions are available when the conversation is idle. The optional story handle is unset in the app.
