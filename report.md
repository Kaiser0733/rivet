# Rivet phone-native chunk 1

Branch `feat/phone-native` was created from `batch/surface-fix` at `09cc2773cf57bcba8fbcc3d7dbc71c3152840aca`.

## 1. Share intake and landing

Rivet classifies shared text, repository links, images, files, and ZIPs. It copies granted content streams into the private cache under a 64 MiB limit. ZIPs still open the skill installer. Other shares prefill a draft in an empty session or a new session.

Commit `c0365a3fcb9194f4507a653c98715a91787cbe45`

- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/com/kaiser/rivet/MainActivity.kt`
- `app/src/main/java/com/kaiser/rivet/chat/ChatViewModel.kt`
- `app/src/main/java/com/kaiser/rivet/chat/ShareIntake.kt`
- `app/src/main/java/com/kaiser/rivet/chat/SharedFileCopy.kt`
- `app/src/main/java/com/kaiser/rivet/ui/RivetApp.kt`
- `app/src/main/java/com/kaiser/rivet/ui/chat/ChatScreen.kt`
- `app/src/test/java/com/kaiser/rivet/chat/ShareIntakeTest.kt`

## 2. Shared-image vision

The user message stores the private image path. The existing encoder supplies transient image data to vision-capable cloud models. Text-only models receive an inbox path and a plain capability note. Chat renders the image or shows a missing-file message.

Commit `9929c4f3f2a6c183e8ad4c7f262df765ca77d29f`

- `app/src/main/java/com/kaiser/rivet/agent/AgentContext.kt`
- `app/src/main/java/com/kaiser/rivet/agent/AgentProtocol.kt`
- `app/src/main/java/com/kaiser/rivet/agent/ContextBudget.kt`
- `app/src/main/java/com/kaiser/rivet/agent/InternalContext.kt`
- `app/src/main/java/com/kaiser/rivet/chat/ChatViewModel.kt`
- `app/src/main/java/com/kaiser/rivet/chat/ShareIntake.kt`
- `app/src/main/java/com/kaiser/rivet/provider/AnthropicClient.kt`
- `app/src/main/java/com/kaiser/rivet/provider/ChatGptPlanClient.kt`
- `app/src/main/java/com/kaiser/rivet/provider/GeminiClient.kt`
- `app/src/main/java/com/kaiser/rivet/provider/ImageAttachmentEncoder.kt`
- `app/src/main/java/com/kaiser/rivet/provider/OpenAiCompatibleClient.kt`
- `app/src/main/java/com/kaiser/rivet/provider/ProviderVisionAttachments.kt`
- `app/src/main/java/com/kaiser/rivet/ui/chat/ChatScreen.kt`
- `app/src/test/java/com/kaiser/rivet/chat/ShareIntakeTest.kt`
- `app/src/test/java/com/kaiser/rivet/provider/SharedImageVisionTest.kt`

## 3. Outbound intents

Cloud coordinators can open apps, HTTPS URLs, and project or inbox files, show the share sheet, or prepare email, message, and event drafts. Four launch tools use BrowserAction approvals. The share tool uses ReadOnly and hands destination choice to Android. Workers receive no phone tools.

Commit `b8f1e5fee1d5790520b7f5a71bfe47d0f4b38e35`

- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/com/kaiser/rivet/agent/AgentProtocol.kt`
- `app/src/main/java/com/kaiser/rivet/agent/AgentToolError.kt`
- `app/src/main/java/com/kaiser/rivet/agent/AgentToolExecutor.kt`
- `app/src/main/java/com/kaiser/rivet/browser/BrowserSession.kt`
- `app/src/main/java/com/kaiser/rivet/chat/ChatViewModel.kt`
- `app/src/main/java/com/kaiser/rivet/chat/SharedFileCopy.kt`
- `app/src/main/java/com/kaiser/rivet/runtime/PhoneIntentSpec.kt`
- `app/src/main/java/com/kaiser/rivet/runtime/PhoneIntents.kt`
- `app/src/main/java/com/kaiser/rivet/subagent/WorkerRunner.kt`
- `app/src/main/java/com/kaiser/rivet/ui/RivetApp.kt`
- `app/src/main/java/com/kaiser/rivet/ui/browser/BrowserScreen.kt`
- `app/src/main/java/com/kaiser/rivet/ui/chat/ChatScreen.kt`
- `app/src/main/res/xml/shared_file_paths.xml`
- `app/src/test/java/com/kaiser/rivet/agent/PhoneApprovalTest.kt`
- `app/src/test/java/com/kaiser/rivet/runtime/PhoneIntentTest.kt`

## 4. Clipboard button

The composer attachment sheet enables a clipboard action based on foreground metadata. The tap reads the clipboard text and appends it to the draft.

Commit `24b84dd0e8f1835d7a92d7d7023308d532ddf40f`

- `app/src/main/java/com/kaiser/rivet/chat/Composer.kt`
- `app/src/main/java/com/kaiser/rivet/ui/chat/ChatScreen.kt`
- `app/src/test/java/com/kaiser/rivet/chat/ClipboardDraftTest.kt`

## 5. Phone access settings

Settings shows the share target and screen-capture consent copy. Advanced opens a static add-on sheet with the Not installed state, Restricted settings steps, and the Play Protect warning.

Commit `43ec3ea4b20d5a1a2403722c6461c76175a0cc98`

- `app/src/main/java/com/kaiser/rivet/ui/provider/SettingsScreen.kt`
- `app/src/test/java/com/kaiser/rivet/ui/provider/PhoneSettingsTest.kt`

## 6. Agent wording

The cloud coordinator prompt describes phone handoffs and one mention of unavailable add-on capabilities. Local prompts say phone tools need a cloud model. Tool status labels and the add-on error explain the actions in plain words.

Commit `a4f1ebf32a5d9e89c3b17dd3bd9f785d34e483e7`

- `app/src/main/java/com/kaiser/rivet/agent/AgentToolError.kt`
- `app/src/main/java/com/kaiser/rivet/chat/ChatViewModel.kt`
- `app/src/main/java/com/kaiser/rivet/local/LocalPrompt.kt`
- `app/src/test/java/com/kaiser/rivet/chat/PhonePromptTest.kt`

## Product decisions

- **D1** GitHub links are recognized only from shared text. The draft asks whether the user wants a summary. Rivet waits for an explicit yes before fetching the repository.
- **D4** App opening accepts a validated package ID or one of eight curated aliases. It does not enumerate installed packages.
- **D5** The full file stays under app-private cache storage as an inbox attachment. This chunk uses the allowed excerpt fallback to avoid introducing a project-path router for a feature that also works without a project. Larger text and code files show their first 8 KiB and a truncation note. Binary files show their saved path. The inbox can be opened or shared through the new phone tools.

## Verification

- All 20 isolated JVM checks passed. Coverage includes five share classifications, vision and text-only request serialization across cloud formats, HTTPS and package validation, email/SMS/event specs, approvals, clipboard draft handling, the Phone section, and prompt/error/activity wording.
- The settings, prompt, and action-approval checks produced assertion failures before their implementations. Share classification, shared-image wiring, intent specs, and clipboard checks reached unresolved-feature errors when compiled before the new APIs existed.
- A separate compilation against Android framework classes checked the share intake method, file-copy helper, image encoder, and phone intent implementation.
- Manifest and clipboard source checks passed. The manifest permission list matches the base, the FileProvider is non-exported, and the only clipboard payload read is inside the attachment tap handler.
- The filtered `bash gradlew :app:testDebugUnitTest --offline` run exited 1 while configuring `:terminal-emulator`. Gradle reported that the Android SDK location was missing, so the Android test task never started.
- `git diff --check` passed.

## Honest gaps

- Android Build remains Hermes’s acceptance gate. The full app build, real Android share delivery, clipboard UI, external intent routing and grants, and session reload were not exercised on a device. The vision wire test used an injected encoder.
- The shared inbox is cache-backed. Android can reclaim cached files, and chat has a missing-image state for that case. The file excerpt fallback remains in effect until a shared path router is available.
- Android’s clipboard metadata disables the button when there is no text clip. An item advertised as text but containing only blank text is detected on the explicit tap.

The add-on copy uses Google’s documentation. [Restricted settings](https://support.google.com/android/answer/12623953) and [Google Play Protect](https://support.google.com/googleplay/answer/2812853).
