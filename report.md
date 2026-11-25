# Round 5 chat surface

## 1. Transcript grouping

The timeline already separates process segments at replies and user input. The chat view opened live action groups by default, then hid completed group summaries inside a separate turn fold. Retry reused the existing transcript without recording a boundary. The activity projection supplied raw shell commands and unbounded outcome descriptions, and three tools fell through to the generic title.

Each process segment now starts with a single collapsed fold. The retry boundary splits history without adding a provider message. Action rows show a verb and target, and only one line of error detail appears inside the fold.

Commit `e4b4416a056fda674ab3022e02d174ac35a9b7f1` · AgentActivityProjection.kt, InternalContext.kt, ChatViewModel.kt, ChatProcessGroups.kt, ChatScreen.kt

JiTTest in AgentActivityProjectionTest.kt. The parent returned three generic titles and failed the first assertion. The branch passed the title, target, and first-error-line assertions. TranscriptWorkerRowTest.kt also exercises the default-collapsed live fold, and the projection suite covers the retry notice.

## 2. Finished workers

The only worker links lived inside collapsible tool details. Finishing the turn hid those links behind the turn summary, even though the detail screen could still load the saved transcript. The worker model text shared space with a fixed-width verb and trailing outcome and was capped at two lines.

One row per worker stays visible through completion and opens the existing worker detail screen. The model line uses the row width and wraps.

Commit `e2129838c02e6184641b9ddc985ea9b4cc1c8b94` · ChatScreen.kt, TranscriptWorkerRows.kt

JiTTest in TranscriptWorkerRowTest.kt. The parent row had no model metadata node at the narrow width. The branch exposed the full line with wrapping enabled and opened the finished worker once when spawn and await receipts both referenced it. The native phone preview showed the model line wrapping between words.

## 3. Streaming Markdown

The live assistant row deliberately used a plain Text composable. It stripped image references but skipped the existing incremental block parser and inline styling. The inline parser also treated an unmatched delimiter as literal text.

Streaming uses the incremental block renderer with provisional inline styles. A partial bold closer and unfinished inline code render without their delimiters.

Commit `e482c40812ce0b3e793a1757c40cf41610f00a0a` · ChatMarkdown.kt, ChatScreen.kt

JiTTest in ChatMarkdownTest.kt. With the parent parser, the first partial bold delimiter leaked as an asterisk. The branch passed every prefix of the reply, including a partial bold closer and a partial filename.

## 4. Inline phone screenshots

The transcript already classified the receipt as an image, but it only retained the project path for display. A project-relative capture could render only through the current project's preview binding and its generic bounded SAF reader. The original capture path lived in the vision attachment, which is empty for text-only models.

The receipt keeps the private capture for the chat preview and the project path as a fallback. Both paths use the existing inline image treatment.

Commit `e6778a8733598e8fb50d35a7bc97134d6561ef97` · AgentProtocol.kt, PhoneCaptureTool.kt, ChatScreen.kt, PresentedFiles.kt

JiTTest in PhoneCaptureToolTest.kt. The parent projected only the project filename for a text-only capture. The branch projected the cached image, kept the project path, and left the model attachment empty.

## 5. Capture notification

The notification body and Capture now used the same capture-service pending intent. The checked-out code could therefore start a capture on a body tap. The notification body had no independent app-opening route. Each arm also posted the same notification through both startForeground and NotificationManager.notify. Its default-importance channel was not configured for heads-up.

The body opens Rivet. Capture now and Cancel remain request-scoped actions. The service posts one foreground notification per arm using a fixed ID and only-alert-once. It preserves an explicitly configured channel and uses a heads-up channel by default where Android allows migration.

Commit `d7a1657feea2b43e176313aab725c0219445ae75` · PhoneCaptureCoordinator.kt, PhoneCaptureService.kt

JiTTest in PhoneCaptureNotificationTest.kt. The parent body resolved to the capture service. The branch test passed the activity and service intent checks, the channel importance check, and the only-alert-once flag check. Android controls heads-up presentation. [Android notification channels](https://developer.android.com/develop/ui/compose/notifications/channels)

## 6. Provider editor widths

Add provider and Change model share the same editor. The header, form, and footer each imposed their own width. The header used a 4 dp start inset, the form and buttons used the page gutter, and the header divider used the whole screen.

Both modes use one full-width column with the standard gutter and one centered cap at wide sizes. The form scrolls between the aligned header and footer.

Commit `c09def99dd93856ca0fc038ec714c3de10d4f9eb` · ProviderEditor.kt

JiTTest in ProviderEditorLayoutTest.kt. The parent back target began 12 dp to the left of the form at every viewport. The branch passed the shared-edge assertions in both modes at all four sizes.

## Verification

- Started from `origin/batch/surface-fix` at `0729dabc`. The repository root had no `AGENTS.md`. The workspace instructions were available in `/home/hatch/AGENTS.md`.
- Ran the red checks against the parent implementation. For Markdown, the same prefix assertions used the parent's parser signature. The worker check used the operation row from its immediate parent.
- Inspected native Robolectric renders at 360 × 800, 800 × 360, 800 × 1200, and 1200 × 800. The previews used the production theme and components with a sample transcript and image. The review covered collapsed and expanded folds, worker metadata, streaming text, inline images, and the header, form, and footer in both provider modes.
- The software emulator reached ADB but its package and activity services stayed unavailable during the attempt. A phone or tablet run of MediaProjection consent, notification heads-up behavior, and an actual capture remains open.
