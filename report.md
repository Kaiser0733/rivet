# Kaiser fix round

Base `55f23dc2` on `origin/batch/surface-fix`. The five fixes are one commit each.

## 1. Anchor the attachment menu

Commit `5fbf66e8cbfd71214495071c113ae52658a8b7b4`

The Camera, Photos, and Files menu now opens above the composer’s + button and aligns with the composer’s starting edge. The three rows retain their icons and labels.

`AttachmentSheetTest` covers the anchor and position calculations for phone and tablet window sizes in both orientations. The standalone source check failed against the parent and passed against the fix. The Gradle test did not produce a result.

Changed files

- `app/src/main/java/com/kaiser/rivet/ui/chat/AttachmentSheet.kt`
- `app/src/main/java/com/kaiser/rivet/ui/chat/ChatScreen.kt`
- `app/src/test/java/com/kaiser/rivet/ui/chat/AttachmentSheetTest.kt`

## 2. Show attachments above the input and support multi-select

Commit `ce80a2f2f2213414217dcc4c4dd4530b061da738`

The composer holds an ordered, horizontally scrollable strip of image thumbnails and file cards. Each card has its own remove button. Photos and Files return batches, and Send includes the staged images and file context in one user message.

`ComposerMultiAttachmentTest` covers both picker contracts, the order of the selected images, file context, and the four cloud request formats. The standalone picker contract check failed against the parent and passed against the fix. The Gradle test did not produce a result.

Changed files

- `app/src/main/java/com/kaiser/rivet/agent/AgentContext.kt`
- `app/src/main/java/com/kaiser/rivet/agent/AgentProtocol.kt`
- `app/src/main/java/com/kaiser/rivet/agent/ContextBudget.kt`
- `app/src/main/java/com/kaiser/rivet/agent/InternalContext.kt`
- `app/src/main/java/com/kaiser/rivet/chat/ChatViewModel.kt`
- `app/src/main/java/com/kaiser/rivet/chat/ShareIntake.kt`
- `app/src/main/java/com/kaiser/rivet/provider/AnthropicClient.kt`
- `app/src/main/java/com/kaiser/rivet/provider/ChatGptPlanClient.kt`
- `app/src/main/java/com/kaiser/rivet/provider/GeminiClient.kt`
- `app/src/main/java/com/kaiser/rivet/provider/OpenAiCompatibleClient.kt`
- `app/src/main/java/com/kaiser/rivet/provider/ProviderVisionAttachments.kt`
- `app/src/main/java/com/kaiser/rivet/ui/chat/ChatScreen.kt`
- `app/src/main/java/com/kaiser/rivet/ui/chat/ComposerAttachmentStrip.kt`
- `app/src/main/java/com/kaiser/rivet/ui/chat/ComposerPickers.kt`
- `app/src/test/java/com/kaiser/rivet/provider/ComposerMultiAttachmentTest.kt`

## 3. Pass project images to vision and hide streamed markup

Commit `b12033300082aa51805e0a832f4653f936f4e976`

The coordinator can use `read_image` to inspect a project image. `present` also passes project image bytes through the provider’s vision request. Text-only models receive a clear status. The transcript suppresses image markup during streaming and holds the image position empty while its preview loads.

`ProjectImageVisionTest` covers reads, presentations, text-only status, provider serialization, and partial Markdown. The standalone tool-wiring check failed against the parent and passed against the fix. The isolated Kotlin rendering check passed all 36 prefixes of an image reference and preserved the prose after it. The Gradle vision tests did not produce results.

Changed files

- `app/src/main/java/com/kaiser/rivet/agent/AgentProtocol.kt`
- `app/src/main/java/com/kaiser/rivet/agent/AgentToolExecutor.kt`
- `app/src/main/java/com/kaiser/rivet/agent/ContextBudget.kt`
- `app/src/main/java/com/kaiser/rivet/agent/ProjectImageRead.kt`
- `app/src/main/java/com/kaiser/rivet/chat/ChatViewModel.kt`
- `app/src/main/java/com/kaiser/rivet/local/LocalToolProtocol.kt`
- `app/src/main/java/com/kaiser/rivet/provider/AnthropicClient.kt`
- `app/src/main/java/com/kaiser/rivet/provider/ChatGptPlanClient.kt`
- `app/src/main/java/com/kaiser/rivet/provider/GeminiClient.kt`
- `app/src/main/java/com/kaiser/rivet/provider/OpenAiCompatibleClient.kt`
- `app/src/main/java/com/kaiser/rivet/provider/ProviderVisionAttachments.kt`
- `app/src/main/java/com/kaiser/rivet/subagent/WorkerRunner.kt`
- `app/src/main/java/com/kaiser/rivet/ui/chat/ChatMarkdown.kt`
- `app/src/main/java/com/kaiser/rivet/ui/chat/ChatScreen.kt`
- `app/src/main/java/com/kaiser/rivet/ui/chat/FileReferences.kt`
- `app/src/test/java/com/kaiser/rivet/provider/ProjectImageVisionTest.kt`

## 4. Show the full worker model catalog

Commit `a8fe3534150132e5fcaffde3bfdb90364eacb937`

Worker settings use the same provider catalog loader as the chat picker. Each expanded group has search and a capped, independently scrolling list with single-line model IDs. A catalog pick can resolve to a different model from the provider’s current chat default.

`SubagentsSettingsTest` covers a multi-model catalog, search, and resolution for both ticked and fixed models. The standalone catalog and height-cap check failed against the parent and passed against the fix. The Gradle picker and pool tests did not produce results.

Changed files

- `app/src/main/java/com/kaiser/rivet/subagent/SubagentPool.kt`
- `app/src/main/java/com/kaiser/rivet/subagent/SubagentSupervisor.kt`
- `app/src/main/java/com/kaiser/rivet/ui/UtilitySurfaces.kt`
- `app/src/main/java/com/kaiser/rivet/ui/provider/ProvidersViewModel.kt`
- `app/src/main/java/com/kaiser/rivet/ui/provider/SettingsScreen.kt`
- `app/src/main/java/com/kaiser/rivet/ui/provider/SubagentsSettings.kt`
- `app/src/test/java/com/kaiser/rivet/ui/provider/SubagentsSettingsTest.kt`

## 5. Keep a downward scroll under the reader’s control

Commit `7d1f89a177ed63e50709e4a4e4d902aab44afd15`

The transcript suspends follow while the reader scrolls. A gesture that stops short keeps the reader’s position. Reaching the bottom or choosing Jump to latest resumes follow for a streaming reply.

Before patching, an isolated Kotlin replay using the actual parent transcript predicates scheduled 36 px of extra scrolling after a simulated gesture stopped short. The replay using the fixed predicates scheduled 0 px and resumed following after the simulated gesture reached the bottom. `TranscriptScrollTest` exercises the real Compose transcript, Jump to latest, and streamed growth. Its Gradle run did not produce a result.

Changed files

- `app/src/main/java/com/kaiser/rivet/ui/chat/ChatScreen.kt`
- `app/src/test/java/com/kaiser/rivet/ui/chat/TranscriptScrollTest.kt`

## Honest gaps

- The targeted `:app:testDebugUnitTest` runs did not reach JUnit. Early attempts hit Gradle daemon communication and Android plugin resolution errors. The repository workaround got past plugin resolution, then one run exhausted `/tmp`. A retry with the cache on the workspace volume reached `:app:checkDebugAarMetadata` before the runtime restarted. No JUnit XML or completed Gradle result was available.
- The standalone checks and Kotlin replays establish specific source and logic behavior. A Compose replay of the parent, physical touch and fling behavior, portrait and landscape layout on phones and tablets, platform picker interactions, and live provider image responses and catalogs remain unverified.
- All six reference images were opened before the relevant edits. `git diff --check 55f23dc2..7d1f89a1` passed. No APK was built, and CI was left for Hermes.
