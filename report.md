# Rivet improvements report

Branch `improvements`, based on `18013811` from `batch/surface-fix`. Reviewed code through `fd961612`.

## Items and commits

The first commit, `fe81b0ac`, records the implementation and verification plan. Paths in this table are relative to `app/src/main/java/com/kaiser/rivet` unless noted. Each SHA’s diff is the full file list for that item.

| Item | Commit | Where it landed |
| --- | --- | --- |
| 1. Secondary screens | `62a0e38c` | `ui/UtilitySurfaces.kt` defines the icon-led row and lead surface. Tools, Projects, Models, and Settings use them. Phone, Memory, Subagents, Skills, Account, Appearance, and About get a visible purpose and first action. |
| 2. Details in folds | `c50a6eb5` | `ui/UtilitySurfaces.kt`, the Settings, Subagents, Skills and Support screens in `ui/provider`, and `ui/local/ModelsScreen.kt` hide instructions and detail until opened. Includes Termux setup, project memory, model fit, worker choices, and support. |
| 3. Home starters | `c269525d` | `ui/starter/StarterGallery.kt`, `ui/chat/ChatScreen.kt`, `ui/workspace/ProjectsScreen.kt`, and `ui/RivetApp.kt` move the four starters above the empty composer. The SAF picker and installer use the same pending starter. Four vector glyphs live in `app/src/main/res/drawable`. |
| 4. Motion | `25cfdcf5` | `ui/Theme.kt` and `ui/EntryMotion.kt` drive drawer and route motion, folds, slash panel, split preview, and pressed states in the app shell and shared surfaces. |
| 5. Landscape | `9f3791df` | `ui/chat/ChatLayout.kt`, `ui/chat/ChatScreen.kt`, and `ui/chat/FilePreview.kt` share the measured conversation lane and pane sizing. Wide Settings, Projects, Tools, Models, and Browser arrange related content horizontally. The sidebar links and recents share scroll space on short screens. |
| 6. Assistant text | `f3895901` | `ui/chat/AssistantInlineText.kt` and `ui/chat/ChatScreen.kt` return plain completed paragraphs to a String-based text path and keep only real Markdown spans. The exact glyph-overlap cause is still open. |
| 7. Hardware input | `1967cf5a` | `chat/Composer.kt`, `ui/HardwareInput.kt`, `ChatScreen.kt`, and shell and panel files add hardware Enter, Shift+Enter, Escape, mouse selection, hover, and visible keyboard focus. Lists use their existing Compose scroll containers for wheel events. |
| 8. Sidebar identity | `dfaf444c` | `ui/history/HistoryScreen.kt` uses the real launcher foreground before the RIVET wordmark. |
| 9. Empty chats | `8cb5834b` | `storage/CodingSessions.kt`, `chat/ChatViewModel.kt`, and `ui/history/HistoryScreen.kt` filter empty Recents entries and reuse the same project-scoped draft session. |
| 10. Reasoning effort | `36ed91af` | `ui/chat/ReasoningSignal.kt`, `ModelSheet.kt`, and `ui/Theme.kt` replace the old effects with one in-row sweep, from one bar at Low to six at Ultra. |
| 11. Manual compaction | `3b7ec755` | `chat/ChatViewModel.kt`, `storage/CodingSessions.kt`, `agent/CompactionNotice.kt`, `AgentActivityProjection.kt`, `InternalContext.kt`, and the chat renderers show the live status, keep the counted completion after reload, and keep status rows out of model requests. |
| 12. Context ring | `26c45a2b` | `chat/ContextMeter.kt`, `ui/chat/ContextMeter.kt`, `storage/ContextUsageDelta.kt`, `CodingSessions.kt`, and `ChatViewModel.kt` provide provider-anchored usage when available and refresh the ring with compaction. |
| 13. Model deduplication | `5732f493` | `ui/local/ModelRows.kt` and `ModelsScreen.kt` key one ordered catalogue by model ID and reuse the same row and action placement for all model states. |
| 14. Checkpoints | `09687a11` | `ui/processes/ProcessesScreen.kt` and `ui/UtilitySurfaces.kt` show one line per closed checkpoint and reveal its title, metadata, and actions within the open row. |
| 15. Full row values | `b1623128` | `ui/provider/SettingsScreen.kt`, `ui/provider/SkillSettings.kt`, `ui/chat/ModelSheet.kt`, and `ui/history/HistoryScreen.kt` give values room to wrap beneath labels. |
| 16. About rows | `62a5d888` | `ui/provider/SettingsScreen.kt` uses the same row for Updates and Conversation usage. |
| 17. Recents timestamps | `24b3fc3f` | `ui/history/ChatRecency.kt`, `HistoryScreen.kt`, and `app/src/main/res/values/strings.xml` use “Just now”, minutes, hours, and days for all ages. |
| 18. Support | `679e4871` | `ui/provider/SupportSection.kt` and its strings remove the personal payment handle and keep the coffee link. |
| 19. Deliverables | `57d9185a` | `agent/AgentToolExecutor.kt`, `PresentedFile.kt`, `chat/ChatViewModel.kt`, and `local/LocalToolProtocol.kt` instruct the coordinator to present finished files meant for the user and exclude incidental artifacts. |
| 20. Inline file links | `bcd9322a` | `ui/chat/AssistantInlineText.kt`, `FileReferences.kt`, `ChatScreen.kt`, `chat/ChatViewModel.kt`, and `workspace/WorkspaceFilePreview.kt` resolve highlighted project files using the existing preview path. |

Follow-up source audit `3840b88d` closes wide-layout and disclosure edges. Compile correction `fd961612` imports the context budget, refreshes Recents after a successful turn save, and repairs the memory page’s status expression. Both commits address numbered work.

## Verification

| Check | Result |
| --- | --- |
| `python3 -m unittest discover -s scripts -p 'test_*.py'` | 83 passed. |
| Source regressions on each change’s parent | 18 checks failed at their respective parents and passed on the reviewed branch. Includes landscape width, plain-text rendering, empty Recents, compaction visibility, and file references. |
| Isolated JVM tests | 24 passed. Covers chat width, hardware Enter policy, context reading and signed delta, timestamps, Markdown, span emission, link annotations, effort levels, and model rows. |
| App-class JVM tests | 48 passed against the compiled application classes. Suites were `CompactionNoticeTest`, `AgentActivityProjectionTest`, `ContextBudgetTest`, `InternalContextTest`, `ManualCompactionTest`, and `LocalPromptBudgetTest`. |
| Standalone source compile | Exit 0. All main app Kotlin sources and terminal Java sources compiled using Android API 35 class stubs, the project’s Compose libraries, Kotlin 2.0.21 available locally, and resource IDs checked against the checkout. 136 resource symbols validated. This was a source check outside AGP. |
| Gradle `testDebugUnitTest` | Stopped at configuration because the Android SDK was unavailable. The full AGP and Robolectric suites, resource linking, device behavior, and exact pinned Kotlin 2.1.10 build await Android Build. |
| Native glyph probe | The standalone process could not initialize Android’s font runtime. No pixel-level conclusion. |
| `git diff --check` | Clean. The diff contains no staged screenshot deletions, version changes, updater changes, manifest changes, or build-script changes. |

The Recents SQL was also checked against an in-memory database with blank, brief-only, and sent chats. The first user event moves the corresponding chat into Recents. The motion and timeline source checks cover wiring, and the JVM tests cover data and projection. The phone round still owns what the user sees and how it feels.

## Plan decisions and deviations

- **Home:** the four starter rows share the existing pending-starter and SAF install workflow. They sit above the composer and use a height-limited scroll on shorter viewports. The context indicator takes a small slot beside the model selector on wide composers. On narrow composers it sits above the control row so the selector, folder, and send controls retain their space.
- **Empty chats:** one reusable empty draft per project keeps its draft key. Recents excludes zero-message and context-only sessions. A single empty record can be reused after returning from an existing chat.
- **Models:** the recommendations lead the one identity-keyed catalogue. Download, progress, and installed controls share the same row family, and export/delete remain available in the installed row’s fold.
- **Provider usage:** the meter adds the signed estimated difference to the provider’s last compatible reported input count. Its database field is nullable so older observations without paired estimates fall back to estimation. The compaction message counts the retained history and checkpoint. The ring accounts for the whole next request, including system and tool definitions.
- **File references:** assistant inline code links are resolved as regular files under the active project. They use the deliverable preview workspace, stat, and open path. Missing or revoked references fall back to ordinary code text.
- **Text rendering:** the old completed path applied paint spans to every run, including plain text. Styled text could also repeat the paragraph’s font properties. The new path applies spans only to real Markdown styling and sends plain content through the same String-based renderer as streaming. That source-level divergence is verified. The reported device’s glyph collision has not been reproduced, so the change is not a confirmed root-cause diagnosis for that specific collision.
- **Final review:** the follow-up commit finishes the wide detail pages, keeps Phone access’s Advanced group reachable, shares disclosure motion for finished processes and provider setup, and refreshes the meter at the storage-pressure compaction path.

## Motion audit

I traced the drawer, page transitions, disclosure panels, slash panel, split preview, press and focus feedback, reasoning selector, and compacting status against the shared settings in `ui/Theme.kt`. Page, slash, and split transitions settle at zero translation and full opacity. The preview reserves its measured place while the artwork enters a clipped pane. Disclosures finish at the viewport’s measured content height when open and zero when closed. Press and focus paint the existing bounds, and the composer’s send scale returns to 1. The calibration bars draw within fixed row slots. The compacting dots occupy a fixed-width slot for all three frames. The chat turn fold keeps its previous 180/150 ms values and curve, now defined in the theme.

The app’s explicit drawer commands use the shared drawer duration. Android’s built-in drag and scrim gestures are still handled by Material. The named app animations respect the system duration scale or use a static result when it is zero. Eye-level motion, drag feel, and settled pixel positions still need device checks.

## Your additions

None. Every change is tied to a numbered item.

## Honest gaps

- **Item 6 remains open for a causal device reproduction.** The renderer and span tests prove the code-path correction. They do not recreate the overlapping letters. A redacted raw reply that reproduces the collision, the device’s font/display scale, and a before/after rendering on that device are the evidence that could establish the cause.
- **Phone verification.** No device was connected through ADB. Hermes’s build and test round still need portrait and landscape screenshots for all named screens, fold and drawer motion with system motion on and off, keyboard and mouse interactions, the starter SAF workflow, live compaction from the composer, real deliverable use, and split-preview taps when a file is present, deleted, or from a different project.
- **The Android Build gate has not run on this branch as part of this task.** Hermes owns that gate. Local checks below do not replace it.
