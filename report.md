# Rivet compaction upgrade

Built on `feat/compaction` from `batch/surface-fix` at `84f7f52d629aeee615d98027fc6afcaa486752ea`.

| Item | Change | Files | Commit |
| --- | --- | --- | --- |
| Checkpoint template | Store and render all six sections in fixed order. Cloud and local providers share the same merge prompt and checkpoint format. Legacy summaries can still be read. | `agent/TaskCheckpoint.kt`, `agent/InternalContext.kt`, `chat/ContextPreparation.kt`, `chat/TaskCheckpointTest.kt` | `096913424d4ac3dd150f12d69a4dda8143951819` |
| Merge and cap | Apply new entries to the previous checkpoint. Keep existing decisions and constraints and retain the original text when marking an entry `SUPERSEDED`. Fit the checkpoint to the existing 8 KiB ceiling and model budget by removing the oldest STATE detail first. | `agent/TaskCheckpoint.kt`, `chat/ContextPreparation.kt`, `chat/TaskCheckpointTest.kt`, `chat/ContextPreparationTest.kt` | `096913424d4ac3dd150f12d69a4dda8143951819` |
| Verbatim goal | Pin the accepted user request, including whitespace, outside model control. Retries use the corresponding user event from the active transcript. | `chat/ChatViewModel.kt`, `chat/ContextPreparation.kt`, `agent/TaskCheckpoint.kt`, `chat/ChatViewModelTest.kt`, `chat/TaskCheckpointTest.kt` | `096913424d4ac3dd150f12d69a4dda8143951819` |
| Tool result pre-pruning | When formatting the removed span, results over 8,192 characters contribute the first 4,096 and last 1,024 with an omission marker. Shorter bodies are included in full when the input budget permits. Existing redaction still applies. The summarize path uses the original removed span when active-projection planning generates tool stubs. | `agent/AgentContext.kt`, `agent/AgentContextTest.kt`, `chat/ContextPreparation.kt`, `chat/TaskCheckpointTest.kt` | `5ac358b503139fcf0a5d7f0764eefd6034345ee3`, `096913424d4ac3dd150f12d69a4dda8143951819` |
| Remember discipline | Tell the agent to record established decisions, constraints, and preferences with `remember`. The existing project memory snapshot remains available through compaction. | `chat/ChatViewModel.kt`, `chat/ChatViewModelTest.kt` | `096913424d4ac3dd150f12d69a4dda8143951819` |

 owns the compaction request and merge.  supplies the existing thresholds and needed no changes.

## Verification

- Isolated JVM run using JUnit 4.13.2. The parent with the updated tests produced 9 expected failures out of 47. The feature run passed all 47. The run covered tool inputs, checkpoint creation and chained merges, the existing budget and memory tests, legacy task-state parsing, and the workspace prompt rule. Local and cloud preparation used fake provider responses. The workspace prompt check used the production function extracted from .
-  with the relevant filters exited 1 during  configuration because the Android SDK location was unavailable. No Android or Robolectric tests ran.
-  passed.

## Honest gaps

- The Android Build gate is pending Hermes's run. SQLite persistence and the updated ChatViewModel integration tests require that gate. No live provider or local model was exercised.
- If the verbatim goal and protected notes exceed the available checkpoint budget after all STATE detail is removed, that merge cannot produce a checkpoint. An eligible tool-only compaction can still reduce the active transcript and keep the existing checkpoint. Earlier tool bodies already stored as stubs in the active transcript remain stubs.
