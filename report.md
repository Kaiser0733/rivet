# Rivet phone-native chunk 2

Branch `feat/phone-native-2` starts at `da19c8e69eb0ea660f7f7765fdd1bc09e6ab82c7`, the fetched head of `batch/surface-fix` with chunk 1 present. The base stayed pinned throughout the task.

## A. Granted folders and inbox

- `cf5a22c045965849506d482abdab0366a7d696fc` adds the `phone_grants` SharedPreferences registry, live permission checks, and the folder picker. The workspace files are `GrantPath.kt`, `GrantedFolders.kt`, `AndroidGrantedFolders.kt`, `WorkspaceSelection.kt`, `SafWorkspace.kt`, and `WorkspaceTextRegion.kt`. Removing a registry entry preserves a permission still owned by the selected project.
- `RoutedAgentWorkspace.kt`, `AgentToolExecutor.kt`, `AgentToolError.kt`, `ChatViewModel.kt`, `SharedFileCopy.kt`, `PhoneIntents.kt`, `SettingsScreen.kt`, and `FileScopeRegistry.kt` route bare paths to the selected project, reuse the SAF adapter for `grants/<label>`, and list or page `inbox/` as read-only. Git tools return `repository=false` for granted paths. D2 allows writes with ordinary WorkspaceMutation approval and a warning that external files have no checkpoint or Undo. Moving between roots is refused.
- The checkout had a flat inbox. New shared files are assigned to the receiving session, and existing chat images retain the absolute paths stored in their messages. Old flat entries have no reliable session ownership metadata and are outside the new inbox router. The inbox supports listing and paged text reads, and binary attachments can be opened through the existing phone tools.
- `f2adb22dd19d5b5f68ec21f7dc7c56a53e1244a6` adds the pre-registration worker guard in `WorkerBrief.kt` and its regression in `InboxRoutingTest.kt`. `GrantPathTest.kt`, `GrantRegistryTest.kt`, and `InboxRoutingTest.kt` cover paths, lifecycle, read-only inbox access, and worker isolation.

## B. Termux bridge

- `377467b80ca6c577c49e29bb767a405b5220b732` adds `TermuxCommand.kt`, `TermuxBridge.kt`, and the Phone access status, setup, and test controls in `SettingsScreen.kt`. `AgentProtocol.kt`, `AgentToolError.kt`, `AgentToolExecutor.kt`, `ChatViewModel.kt`, `WorkerRunner.kt`, and `AndroidManifest.xml` wire the separate cloud-only command and elevated ForegroundCommand approval. The UI explains how to grant Termux's command permission in Android Settings.
- The public service is `com.termux.app.RunCommandService`. It forwards internally to the non-exported `TermuxService`. The bridge builds Bash arguments without splitting the original command, uses `RUN_COMMAND_PENDING_INTENT`, and reads the nested `result` bundle with `stdout`, `stderr`, `exitCode`, `err`, and `errmsg`. The modern `app-shell` runner is passed explicitly while the intent's BACKGROUND value is false. [Termux service](https://github.com/termux/termux-app/blob/8629e632fcb95da272221be327db653fb24befe9/app/src/main/java/com/termux/app/RunCommandService.java) · [Manifest](https://github.com/termux/termux-app/blob/8629e632fcb95da272221be327db653fb24befe9/app/src/main/AndroidManifest.xml#L196-L208) · [Result bundle](https://github.com/termux/termux-app/blob/8629e632fcb95da272221be327db653fb24befe9/termux-shared/src/main/java/com/termux/shared/termux/TermuxConstants.java#L1098-L1114)
- The file fallback uses per-command `.rivet-termux/<id>/out.txt` and `code` under a writable primary shared-storage project or grant. It returns a file path for paging or partial output when available. The Settings test and commands without a usable shared folder rely on the callback. `TermuxBridgeTest.kt` and `PhoneApprovalTest.kt` cover the intent spec, validation, timeout, quoting, and approvals.

## C. Phone capture

- `7cd61421b5d3180860d4f429cbf7316f6b7023d2` adds `PhoneCaptureState.kt`, `PhoneCaptureTool.kt`, `ui/phone/PhoneCaptureCoordinator.kt`, and `ui/phone/PhoneCaptureService.kt`. `MainActivity.kt` owns the consent launcher and `RivetApplication.kt` owns the coordinator. `AndroidManifest.xml` adds the non-exported mediaProjection service and its foreground-service permission.
- D3 uses the notification action. Following a fresh consent result, the service moves into the foreground before obtaining the projection. `phone_shot_armed` is an in-flight chat status while the tool waits for the action. The service allows a short delay for the notification shade to close, takes a frame, saves a private PNG, and releases the projection. Rivet labels the screenshot using the app's visibility at capture. `PhoneCaptureTool.kt` applies the existing browser-shot vision gate and emits the PNG path when a model supports images.
- `AgentProtocol.kt`, `AgentToolError.kt`, `AgentToolExecutor.kt`, `RunJournal.kt`, `ChatViewModel.kt`, and `ProviderRuntimeSource.kt` wire the cloud-only ReadOnly tool and pause the run on denial or cancellation. `PhoneCaptureStateTest.kt`, `PhoneCaptureToolTest.kt`, and `PhoneApprovalTest.kt` cover the one-shot transitions, correlation, model gating, and Ask-mode behavior.

## Verification

- The combined isolated kotlinc and JUnit 4.13.2 run passed **29 tests**. It included GrantPath, GrantRegistry, InboxRouting, TermuxBridge, PhoneCaptureState, PhoneCaptureTool, PhoneApproval, ShareIntake, ClipboardDraft, and PhoneIntent.
- Parent evidence included unresolved new grant and capture seams, the Termux effect assertion failing with `ReadOnly`, and the worker scope test failing before the new validation. The new Termux intent and capture tool seams are absent on their feature parents.
- An isolated Kotlin compile against the official API 35 framework jar passed for the SAF registry and workspace adapter, Termux bridge, and capture coordinator/service. This compile used small harness stubs for unrelated app bootstrap and protocol dependencies.
- Manifest comparison found exactly the two specified permission declarations and one mediaProjection service. The version, updater, and local tool profile source passed the scope check. `git diff --check` passed.

## Honest gaps

- Gradle's Android unit suite and a full application compile were unavailable in this Termux environment. Hermes owns the Android Build CI gate.
- The SAF picker and provider behavior, Termux setup and result delivery on installed app versions, and Android 14+ consent, notification dismissal, and first frame all need device verification. Android can filter package visibility on Termux versions without compatible share handlers. The fallback needs a writable shared-storage folder accessible to Termux. Callback-only commands cannot supply partial output if Termux returns nothing.
- OEM behavior and user timing can affect whether the notification shade is visible in the image. Rivet's label uses its own lifecycle visibility and does not identify another app.
