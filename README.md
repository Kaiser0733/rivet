# Rivet

Rivet is an Android-native, chat-first AI coding agent. Choose a project folder
and a model, describe the work, and let Rivet inspect files, make changes, and
run project commands on your phone or tablet.

## Watch it in action

https://github.com/user-attachments/assets/b83108c7-88a5-41f8-a440-a76f53d872a5

## What it does

- Reads, searches, edits, and patches files in a selected project.
- Runs on-device commands, shows their progress, and lets you stop them.
- Keeps recoverable checkpoints for conflict-safe Undo and inspects Git without
  changing your repository history or staging area.
- Supports saved conversations, pins, model switching, project instructions
  from `AGENTS.md`, and automatic context reduction for longer tasks.
- Offers Ask, Basic YOLO, and YOLO approval modes, local static previews,
  bounded project downloads, and Rose or Dark appearance.

## Why Rivet

Project work starts with a conversation. File access, command preparation,
synchronization, and recovery happen underneath it; you do not need to operate
an IDE or install Termux. You choose the model service and supply its API key.

## Providers

OpenAI, OpenRouter, Anthropic, Gemini, and OpenAI-compatible endpoints with a
custom base URL. Model listing depends on the provider; manual model entry is
available when listing is not supported.

## Requirements

- Android 8.0 or newer (API 26).
- An internet connection for model requests and downloads.
- Your own provider API key; provider usage may incur charges.
- Project access granted through Android's folder picker.

## Install

Production APKs use **`Rivet-v<version>.apk`** and are distributed through
[GitHub Releases](https://github.com/Kaiser0733/rivet/releases).

Download the APK and open it through Android or your file manager. Android may
ask you to allow installation from that source, and Play Protect may scan or
warn about an unfamiliar APK. Rivet does not suppress those checks.

## Updating

Use **Settings → Check for updates**. Rivet checks the official published
release, downloads its APK, and verifies the package, version, and signing
certificate before saving it to Downloads. On Android 8–9, a save picker lets
you choose the destination. Open the saved APK to install it yourself.
There are no automatic update checks or automatic installations.

## Safety and limitations

- You explicitly select the project folder. Rivet can modify its files;
  approval requirements depend on the mode you select.
- Commands run with Rivet's Android app privileges. They are **not an OS
  sandbox** and can access app-private data available to that app.
- Undo is guarded against newer changes, but checkpoints are not backups.
  Use source control or backups for important projects.
- Provider keys are encrypted on this device and sent to the configured
  provider with requests. They are not added to the command environment.
- Native text edits support UTF-8 files up to 1 MiB. Document-provider
  capabilities and external changes can prevent an operation.

See [SECURITY.md](SECURITY.md) for vulnerability reporting.

## Building from source

GitHub Actions is the authoritative build environment. It runs Python checks,
Android unit tests, lint, and APK verification. A local debug build requires
JDK 17, Android SDK 35, and NDK 27.2.12479018:

```sh
./gradlew assembleDebug
```

Signing and public release steps are in [RELEASE_PROCESS.md](RELEASE_PROCESS.md).

## Support

Rivet is free and built by Vastraa Labs, a two-person indie studio. If it saves
you time, you can support the next build:

- [Buy us a coffee](https://www.buymeacoffee.com/Vastraalabs)
- UPI: `maybeamardeep@fam`

Every contribution goes into development — better models, faster releases,
bigger ideas.

## License

Rivet-authored code is [Apache-2.0](LICENSE). Incorporated components retain
their licenses; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
Rivet is not affiliated with Termux.
