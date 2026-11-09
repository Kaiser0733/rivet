# Rivet

**Code with AI, right on your phone.**

No laptop. Pick a project folder, pick a model, tell Rivet what to change.
It reads your files, makes the edits, runs the commands — and asks first,
if you want it to.

[![Release](https://img.shields.io/badge/release-v1.1.1-brightgreen)](https://github.com/Kaiser0733/rivet/releases/latest)
[![Android](https://img.shields.io/badge/Android-8.0%2B-brightgreen)](https://github.com/Kaiser0733/rivet/releases/latest)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)

**[⬇ Download the APK](https://github.com/Kaiser0733/rivet/releases/latest)** · [Support the build](https://www.buymeacoffee.com/Vastraalabs)

Built by [Vastraa Labs](https://vastraalabs.online).

## See it

https://github.com/user-attachments/assets/b83108c7-88a5-41f8-a440-a76f53d872a5

<p>
  <img src="docs/screenshots/home.jpg" width="32%" alt="Rivet home screen — pick a project and ask">
  <img src="docs/screenshots/chat.jpg" width="32%" alt="Rivet at work — it made a file and left the existing one alone">
  <img src="docs/screenshots/settings.jpg" width="32%" alt="Rivet settings — providers, modes, themes">
</p>

## What it does

- Reads, searches, edits, and creates files in the folder you pick.
- Has a real terminal, built in: shell, Python 3.12, Git, BusyBox.
  No Termux needed.
- Runs keep going in the background, with a notification that shows
  what's happening. Kill the app and a run can resume where it stopped.
- Set a goal per project (`/goal`) — Rivet remembers it every session.
- Project memory: facts Rivet should keep, saved in `.rivet/memory.md`.
- Skills: install Agent Skills from GitHub or a zip, run them with `/name`.
- Checkpoints before risky steps. Undo a whole run from the Work tab.
- Spend caps per run and per month, if you want them.
- Switch provider, model, or thinking level mid-chat. No restart.
- Three modes: **Ask** (asks before everything), **Basic YOLO** (small
  stuff runs on its own), **YOLO** (no prompts).
- Reads `AGENTS.md` in your project, so your rules stick.

## Providers

Bring your own key: OpenAI, Anthropic, Gemini, OpenRouter, or any
OpenAI-compatible API. You can also sign in with a ChatGPT plan.

Your key stays on your phone, encrypted. We never see it.

## What you need

- Android 8 or newer.
- Internet, for the model.
- Your own API key. Your provider may charge you. Rivet doesn't.

## Install

Download the APK from [Releases](https://github.com/Kaiser0733/rivet/releases/latest) and open it.
Android will ask if that's okay — it does that for every app outside the
Play Store.

If GitHub is slow or unreachable, the same file is mirrored at
[vastraalabs.online/downloads](https://vastraalabs.online/downloads/Rivet-v1.1.1.apk).
It is the identical signed app.

## Updates

In the app: **Settings → Check for updates**. Rivet downloads the new APK,
checks it's really ours, and saves it to Downloads. You tap it to install.
Nothing installs on its own.

## The honest part

- Rivet only touches the folder you pick. It can change files in it.
- Commands run like a normal app on your phone. There is no sandbox.
  Read what you approve.
- Undo is not a backup. Keep your project in Git or copied somewhere safe.
- Text edits work on files up to 1 MB.

More in [SECURITY.md](SECURITY.md).

## Build it yourself

You need JDK 17, Android SDK 35, and NDK 27.2.12479018.

```sh
./gradlew assembleDebug
```

CI builds and checks every change. Release signing is in
[RELEASE_PROCESS.md](RELEASE_PROCESS.md).

## Support

Rivet is free. If it saved you time:
[Buy us a coffee](https://www.buymeacoffee.com/Vastraalabs).

## License

Apache-2.0. Parts we borrowed keep their own licenses — some are GPL,
and their source is in this repo. See
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). Rivet is not part of
Termux.
