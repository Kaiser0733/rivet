# Rivet N1–N7 device-report fixes

Branch `fix-n1n7` starts at `6f9f78adaf462b8daef3e539c7375084f36bd134` on `batch/surface-fix`. The parent column gives the immediate parent of each implementation commit. The regression run uses the original branch point's production code with the new tests copied in.

| Item | Implementation | Parent |
| --- | --- | --- |
| N1 | `0d78aa73`, `88a03972` | `6f9f78ad`, `a18a8882` |
| N2 | `12429cfa` | `0d78aa73` |
| N3 | `a18a8882`, `9ba48327`, `d095dc34` | `3908446b`, `88a03972`, `565143ed` |
| N4 | `135b8513` | `12429cfa` |
| N5 | `889972d5` | `135b8513` |
| N6 | `ac8b7e8b` | `889972d5` |
| N7 | `3908446b`, `565143ed` | `ac8b7e8b`, `9ba48327` |

## N1 · Termux replies

The branch point already has the explicit private receiver and decodes Termux's `result` bundle. For runs with a project output directory, `TermuxFileWait.kt` discarded the decoded callback when the files were missing or unreadable. It also waited past a published exit status when both streams were empty. The wrapper redirected both streams, so a valid callback could arrive without any output to recover.

`TermuxCommand.kt` and `TermuxBridge.kt` return a bounded preview of each stream alongside the saved files and keep the command's exit status. `TermuxFileWait.kt` prefers a completed file result, accepts useful callback output when the files are unavailable, and preserves a Termux error if neither path supplies output. The file marker can complete a silent command. The callback transport uses the [Termux RUN_COMMAND result contract](https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent).

JiTTests exercise the saved-output shell wrapper with real stdout, stderr and exit status, the file-wait path with an unreadable project and a usable callback, and a successful command with empty streams.

## N2 · Starter openings

`DsmlToolCalls.kt` already intercepts streamed DSML and dispatches complete calls through the normal agent loop. The tester's envelope has a closed `run_command` invocation followed by an empty `git_status` invocation with no closing tag. The parser lost that second call and could also absorb a following invocation into an earlier unclosed one. It now splits on invocation starts and accepts an omitted closing tag for a blank argument body. Native tool calls retain precedence.

The JiTTest streams the tester's shape one character at a time. It checks that the opening sentence is the only visible text and that both tool names and argument objects reach the agent's tool-call response.

## N3 · Compaction, worker output, and recall

- `CodingSessions.kt` commits the visible compaction notice with the durable checkpoint, including the storage-pressure path. Previously manual and automatic compaction relied on a later save through `ChatViewModel.kt`, and storage-pressure compaction skipped that save. New markers live in the display history. The projection validator still accepts earlier saved markers.
- `ContextPreparation.kt` pins the session's explicit goal when summarizing. `TaskCheckpoint.kt` prioritizes short user quotes that explicitly pin a fact. `ArchiveRecall.kt` recognizes the pinned-number question and the existing archive answer path includes the saved goal for a combined question.
- `ResultEnvelope.kt` and `SubagentContext.kt` bound the coordinator's awaited payload to 6 KiB and each pushed payload to 3 KiB. Previously the limits were 24 KiB for an await and up to 32 KiB for a pushed result. `AgentContext.kt` can compact oversized legacy await results into a structured 3 KiB result while keeping the call/result pair and the original event history.
- `ChatScreen.kt` takes a reader to a newly submitted command reply even when they were scrolled back. `ChatViewModel.kt` remaps transcript metadata when inserting a command notice drops an old visible row.

JiTTests reopen a newly committed checkpoint before any further save, compact a 33-turn history with an oversized worker result in the newest group, check truncation at both worker delivery points, and exercise a 33-turn saved-goal and pinned-5000 round trip. The transcript test scrolls away from a compaction marker, inserts a `/goal` reply, and verifies that the reply comes into view.

## N4 · Phone captures in the project

A bound project already had a SAF write path in `ChatViewModel.kt`. `PhoneCaptureTool.kt` treated the writer as optional and reported the private cache file as a successful deliverable when there was no destination. Successful results now require a capture saved through the conversation's project grant. The model's vision attachment can still use the local capture while the chat image and file tools use the project-relative path.

The JiTTest submits a capture without a workspace writer and checks that it returns a tool error before invoking capture. The bound-path test verifies that the reported project path and the vision image are both available to their consumers.

## N5 · Worker selection

`ChatViewModel.kt` let an earlier transcript read publish after another selection or after close. One selected worker ID now owns the sheet, older reads are cancelled, and `RivetApp.kt` and `WorkerSheet.kt` use the same loaded record for the title and selected chip. The chip uses Compose's selected state for its appearance and accessibility.

The JiTTest closes the sheet with a read pending and checks that the old transcript cannot reopen it.

## N6 · Goal notice

`GoalMemory.kt` put an excerpt of automatically recorded assistant progress inside the `/goal` status. The excerpt was clipped at 160 characters and looked attached to the assistant's reply. Status now shows the goal, state and set date. `SlashCommands.kt` also normalizes invisible formatting within a repeated slash prefix, which could previously send a visually correct command to the unknown-command path.

JiTTests verify status with a long prior assistant answer and dispatch of a slash prefix containing a zero-width character. An intermittent failure from byte-identical plain `/goal` input did not reproduce in code. Its device input bytes were unavailable.

## N7 · Jump to latest

The chip exists at the branch point in `ChatScreen.kt`. Its visibility depended on follow mode and a 72 dp near-bottom threshold as well as the list's scroll state. The chip now appears whenever the conversation can scroll forward, has an accessibility button role, and reattaches follow when pressed.

The JiTTest moves the real Compose transcript a small distance from the bottom, presses the chip and checks that it disappears at the end. The existing longer gesture test covers following a growing reply after the jump.

## Verification

### Gate re-run 45744527879

CI failed two browser tests that this branch does not modify:
`BrowserEvalSessionTest.evalReturnsBoundedSuccessAndOrigin` and
`BrowserKeyModalTest.browserKeyStillSucceedsWithoutAModal`.

Both files, and the browser production code they exercise, are byte-identical
at the branch point `6f9f78ad` and at `dd0cf165`:

    git diff 6f9f78ad..dd0cf165 -- app/src/main/java/com/kaiser/rivet/browser \
                                     app/src/test/java/com/kaiser/rivet/browser

is empty. The six tests in the two classes pass on the parent checkout and on
the branch. The CI failure is therefore parent-red, not branch-broke.

### Browser teardown race

The failure is in `@After`, not in either test body: kotlinx-coroutines-test
reports that `Dispatchers.Main` is used concurrently with `resetMain()`.
`BrowserSession.onPageFinished` starts the post-load budget job, which jumps
to `Dispatchers.Default` and then hands back to `Dispatchers.Main`. When the
test ends before that handoff, the handoff can read Main while the next
`resetMain()`/`setMain()` writes it.

`BrowserSession.awaitPendingWorkForTest()` now cancels and joins the session's
transient renderer jobs. Both test classes wait for it before resetting the
test dispatcher. This is a test-support seam; no production behaviour changes.

### Local results

- `BrowserEvalSessionTest` and `BrowserKeyModalTest`: 6 tests green on
  `6f9f78ad` and on `dd0cf165` with the fix.
- Full `:app:testDebugUnitTest`: 1904 tests, 1903 pass. The only failure is
  `WorkspaceMirrorTest.materializesTextBinaryNestedUnicodeAndEmptyFiles`,
  which also fails on `6f9f78ad` in this container; the CI gate did not
  report it.
- `python3 -m unittest discover -s scripts -p 'test_*.py'`: 100 tests OK.

## Honest gaps

- No handset was attached. The Samsung callback from another Android package, live starter-provider output, Android capture consent and notification, SAF behavior on that device, and visual rendering on its screen still need a device pass.
- The Termux report's package-targeted callback predates the explicit private receiver at the branch point. The reproduced drop on this branch was in the file-result path. The reported Termux storage appops were outside this change.
- The recall fixtures verify Rivet's saved state, provider request preparation, and archive reply. They do not measure factual recall across live providers or reconstruct device prompts beyond the captured evidence.
