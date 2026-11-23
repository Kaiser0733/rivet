# Rivet tester-fix round 3

Branch `tester-fix-3` starts at `598a10e09c0d061ce29cc593e995c12c809e5846` on `origin/batch/surface-fix`. The Termux work has the two commits requested in the brief. The reader verdict has an empty commit so it has a place in the sequence.

## 1. Termux output read-back

Commits `ba5e161edbe8082d8350b9fc3c389f518bfe5e9f` and `0139829c5a3fdb6a353f156e243bdba3a05133f3`.

Files changed · `runtime/TermuxBridge.kt`, `runtime/TermuxFileWait.kt`, `runtime/TermuxCommand.kt`, `agent/AgentToolError.kt`, `TermuxFileWaitTest.kt`, `TermuxBridgeTest.kt`.

An empty successful broadcast now keeps the existing file poller alive for up to five seconds within the command's remaining timeout. The result uses the exit code published in the files. If the files stay unreadable, the error is `termux_output_missing` with a required action, the saved stdout path, and the last workspace failure reason. The wrapper now records stdout and stderr separately. The timeout result retains its `termux_timeout` label and can include whichever stream the workspace can read.

JiTTest · `TermuxFileWaitTest` ran against the original one-read policy extracted into the seam. The late-output and unreadable-file assertions failed. The non-empty broadcast assertion passed on both sides because the original fast path already skipped the file read. The fixed seam passed all 3 tests. `TermuxBridgeTest` ran 9 tests on each side, with the redirect assertion failing on the parent and all 9 passing on the branch. The parent already labeled stdout partial results as timed out. A host Bash run of the branch wrapper passed, with separate stdout and stderr files and exit code 7 published in the status file.

## 2. Example.com reader verdict

Commit `2c3cf0ba7f628a5c3a54b07bcfea0cad168949a7`. Files changed · none.

The supplied research found no H1 in the rendered example.com DOM. The page's script inserts the aria-hidden book SVG and its CSS adds the large top padding. The research ran the production extractor and found that an actual H1 produces a heading block. It also confirmed the reader has a title fallback for pages without an H1. This round made no reader change and ran no new reader test. The Reader-view device check is still open.

## 3. Empty-chat context baseline

Commit `91328361c3441ae837b5307db71a065e4c809984`. File changed · `CoordinatorBudgetTest.kt`.

The measured empty cloud-project fixture uses the production system prompt and tool catalogs, a 16,000-token window, and the 10-byte model identifier `test-model`. Conversation history contains zero messages and zero images. The system prompt and tool declarations account for the bulk of the estimate. The rest is explicit estimator framing.

| Request component | UTF-8 bytes |
| --- | ---: |
| System prompt | 2,238 |
| 50 tool names | 584 |
| 50 tool descriptions | 4,589 |
| 50 tool parameter schemas | 9,197 |
| Tool framing allowance at 96 bytes each | 4,800 |
| Request framing allowance | 128 |
| Model identifier | 10 |
| Empty history serialization `[]` | 2 |
| **Total** | **21,548** |

The tool text comprises 17 browser declarations (4,558 B), 23 workspace declarations (7,047 B), 7 phone declarations (1,838 B), 2 subagent declarations (696 B), and 1 archive declaration (231 B). At the meter's three bytes per estimated token, the total is **7,183 estimated tokens**, or **45%** of this fixture's window. The 4,928 framing bytes are estimator allowances. They do not represent a provider-reported token count. The idle meter is a prospective request estimate, and the app has sent no request for this empty chat.

Both `CoordinatorBudgetTest` cases passed in an isolated Kotlin run using the extracted production catalogs, system prompt and request estimator with empty-history fixtures. The new assertion derives the total from the request parts and checks that the meter matches. There is no red side for this audit because its accounting check passed with the existing estimator. The supplied tester material did not include the exact request or provider usage, so the device's **7,028** was not reproduced.

## 4. Phone screenshot flow

Commit `4b53763336774efe0d6337ca22de1a62ddd36dc5`.

Files changed · `ui/phone/PhoneCaptureService.kt`, `ui/chat/PresentedFiles.kt`, `ui/chat/ChatScreen.kt`, `PresentedFilesTest.kt`, `PhoneCaptureNotificationTest.kt`.

Tapping the collapsed notification invokes the same pending intent as the expanded capture action. The notification tells the user to tap it. Successful captures render in their turn at the existing inline image caps, even with a text-only model. A tap opens the same split preview as other images.

JiTTest · the old transcript projection failed the inline delivery assertion (1 failure of 5). The branch projection passed all 5 in the isolated JVM run. The notification source assertion found no content intent on the parent and found the shared capture intent on the branch. The Android 13 Robolectric notification test is included and did not run locally.

## 5. Read tool contract

Commit `e38f302648bd0b7e58d61a4c7bec7cb830ed291c`.

Files changed · `agent/ReadFileArguments.kt`, `agent/AgentToolExecutor.kt`, `ReadFileArgumentsTest.kt`, `AgentToolExecutorTest.kt`.

The parent Long decoder rejected JSON Schema's integral-number spelling `0.0` for an optional offset on a valid path such as `index.html`. The Read entry point now accepts exact integral JSON numbers, including exponent notation, and rejects fractional, negative, quoted or overflowing offsets, unexpected keys, and malformed paths. The tester's raw argument payload was unavailable, so the specific spelling the device sent remains unconfirmed.

JiTTest · the isolated parser test executed the parent's decoder and failed both cases. The decoder rejected `0.0` and accepted a quoted numeric offset. Both cases passed with the new parser. The executor regression covers the same valid and invalid arguments through `prepare().execute()` and did not run locally.

## 6. Stop the turn from a pending approval

Commit `c45e6f8f9d312760d55c6af0d7b249eb69af8dbf`.

Files changed · `chat/ChatViewModel.kt`, `ui/RivetApp.kt`, `ui/chat/ChatScreen.kt`, `ApprovalDialogStopTest.kt`.

The modal now exposes **Stop turn** beside the existing approval actions. It checks the active approval token and calls the existing turn cancellation path. That path cancels pending gate requests and the send job. The loop persists cancelled results for outstanding calls.

JiTTest · the parent source check found no dialog stop control. The branch check found the control and its token callback. The existing approval-gate suite passed all 6 isolated JVM tests. The Compose test exercises the visible Stop turn button and a cancelled gate request. It did not run locally.

## 7. Goal watcher

Commit `8a011ed3321daefea53e1b81e91a08560514a3c4`.

Files changed · `chat/ChatViewModel.kt`, `chat/ProjectGoalWatcher.kt`, `ProjectGoalWatcherTest.kt`.

The watcher remembers each observation with its workspace identity. A first observation or a still-absent goal is quiet. A file appearing, changing or disappearing in the same workspace is reported. Rivet's own writes update the observation, and a failed refresh preserves the comparison baseline.

JiTTest · the parent comparison failed the new-project and file-appeared assertions (2 failures of 3). The branch watcher passed all 3.

## 8. Batch project memory proposals

Commit `cfdee3961d2a5cc51ccb38e50c12056488d65681`.

Files changed · `agent/AgentLoop.kt`, `agent/AgentProtocol.kt`, `agent/AgentToolExecutor.kt`, `agent/GoalMemory.kt`, `agent/ProjectStateFiles.kt`, `chat/ChatViewModel.kt`, `ui/chat/ChatScreen.kt`, `MemoryProposalBatchTest.kt`.

The coordinator queues a turn's validated proposals across model responses and labels them as proposed. When the turn is ready to finish, it presents all distinct facts in one scrollable card if approval is required. Approval writes the batch through one checkpoint and one version-checked workspace write. Denial applies none.

JiTTest · a two-response fixture produced two cards on the parent. Both the approve and deny cases failed there. Both passed with the branch loop in the isolated JVM run, including the assertions for one write containing both facts and zero writes on denial. A separate run of the production memory formatter passed the combined-write and hard-cap refusal checks.

## 9. Auto-titles after a failure

Commit `21dae2070342f2b5a6af2ecddd6cb2b853f90cf1`.

Files changed · `chat/ChatViewModel.kt`, `chat/ConversationTitle.kt`, `ConversationTitleTest.kt`.

Live and restored turns skip title generation when the answer reports failure or the last tool result group consists of failures. For command results that includes a nonzero exit code. The title sanitizer also rejects a returned failure report. A subsequent successful turn can still supply a title, and titles about investigating a failure are accepted.

JiTTest · the old candidate logic and sanitizer failed the two failure regressions. All 5 tests passed with the branch title policy, including the recovered-command case.

## Honest gaps

- The available frontend-design copy was used for items 4 and 6. It was supplied at `~/workspace/rivet-major-update/frontend-design-skill.md` rather than registered in the tool's skill catalog. Both changes use Rivet's existing components and sizing.
- The Gradle wrapper's Java download failed during TLS. A portable Gradle 8.13 and Android 35 SDK got as far as project configuration after working around the daemon connection. The direct, trust-store, local-mirror and final Gradle test attempts failed resolving Android Gradle Plugin 8.9.2. The build never reached Android compilation or JUnit, including the notification, Compose approval, executor integration and full title lifecycle tests.
- The isolated JUnit runs compile the relevant Kotlin and use narrow fixtures for Android or unrelated dependencies. The parent runs preserve the original logic behind the same test seam. Early isolated compiler or runner setup failures were corrected before collecting the results above.
- No Android device was connected. Real Termux SAF visibility, the collapsed notification, inline screenshot appearance and split preview, the modal stop flow, the example.com Reader toggle, and the on-device meter baseline still need device verification.
