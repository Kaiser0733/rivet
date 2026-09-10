# Rivet Architecture

Describes what exists today. Phase-by-phase growth is recorded in
MASTER_ROADMAP.md; anything not listed here is not in the tree.

## Current shape (Phase 1: Foundation)

One Android module, `:app`, package `com.kaiser.rivet`.

```
app/src/main/java/com/kaiser/rivet/
    MainActivity.kt      # single activity, reads installed versionName
    ui/
        RivetApp.kt      # shell: top bar, adaptive nav, empty states, settings
        RivetDestination.kt  # tab model (label, empty-state text, icon)
        Theme.kt         # dark color scheme, shape set
app/src/test/java/com/kaiser/rivet/ui/
    RivetDestinationTest.kt  # tab order + per-tab resource distinctness
```

- `MainActivity` obtains the installed `versionName` from the package
  manager and hands it to the shell; there is no other state.
- `RivetApp` keeps the selected tab in `rememberSaveable`, so navigation
  survives process death and rotation for free.
- Layout adapts by width: `NavigationBar` under 600dp, `NavigationRail`
  at 600dp and above. Both orientations on phone and tablet get this from
  the same code path; there are no separate layout resources.
- Icons are hand-authored vector strokes under `res/drawable` — no icon
  library dependency.
- `scripts/verify_apk.py` proves the built APK's package identity,
  version, and signer certificate in CI.

## Planned boundaries

Two boundaries the next phases will fill, already agreed:

- `provider/` — model providers, custom OpenAI-compatible endpoints,
  model listing and selection.
- `workspace/` — project filesystem access and change tracking.

They do not exist yet. No code anywhere references them.
