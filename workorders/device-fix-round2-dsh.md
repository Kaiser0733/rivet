# Work order — device-test fix round 2, dsh pass (branch stability-v1.1.0; run AFTER the Argon round-2 pass lands — HEAD will have moved)

Source: kaiser's real-device test of the CI-green v1.1.0 build, 2026-11-01 (screenshots in ~/workspace/user/files/Screenshot_20261101_131903*, _132105*, _132222*). CI is green (674 tests) — these are runtime behaviours CI did not catch. Contract: the audit + your approved Cluster B design. Fix code when behaviour is wrong; never weaken a test to go green.

## Rules
- Local commits only, one per fix, plain messages. NEVER push.
- User-facing strings: plain simple words. Banned: leverage, seamless, unlock, empower, robust, cutting-edge, game-changer, supercharge, elevate, "AI-powered". No filler narration in comments/test names.

## Fix 4 — v1.0.0's leftover pending copy blocks the new build's tools
Device evidence: opening the "3d" project (which carries a pending copy saved by v1.0.0's crashed session — the extracted-Node-tree wreckage from bug #6) greets the user with "Rivet found pending project changes it couldn't safely reconcile…" and the agent's read tools stay BLOCKED behind Discard/Try again; the first tool rows show as "(Stopped)". Only after the user tapped Discard could work start. Diagnose, then fix:
- The pending state was written by the OLD sync engine. The NEW engine (your F11/F12) can sync around symlink/special-file junk and partial conflicts — the inherited pending copy should be re-run through the new reconciliation instead of re-presenting the old dead end verbatim.
- While any such state exists, the agent must not be hard-blocked from reading/working where that is safe; the never-overwrite-newer-files safety rule stays absolute.
- If reconciliation genuinely cannot complete, the banner stays — but say WHICH paths block it (the vocabulary exists since F08/F12; surface it).
Tests: a stored v1.0.0-style pending state over a tree containing a symlink subtree reconciles or reports named paths, without a blanket tool block.

## Fix 5 — launcher icon still reads as the old dark disc
Hermes verified from source: the mascot IS in the mipmaps (commit 98a995b), but the adaptive foreground (res/drawable-nodpi/ic_launcher_foreground.png) places the whole logo — including its own dark margins and ring — small inside the safe zone, so at drawer size it renders as a plain dark circle and the user cannot see any change. Regenerate:
- Crop the source art (~/workspace/goals/rivet-zero-money-launch/files/rivet-logo-final.png) tight to the ring/face; the face must fill the adaptive safe zone at full contrast.
- Background layer colour = the art's own black so no slab edge shows.
- Legacy ic_launcher/ic_launcher_round PNGs: full-bleed art, no extra margins.
Report the before/after sizes of the face area as a fraction of the icon.

## Fix 6 — long-press copy must work like ChatGPT's
Device evidence: kaiser could not copy text from his own messages or the model's output; your Cluster D long-press copy did not land discoverably on device. Check what the current long-press actually does in code first. Required: long-press on ANY message bubble (user or assistant) brings up the copy affordance — a small menu with "Copy" (copies the whole message) and, if the codebase makes it feasible without a rewrite, "Select text". A bare invisible copy-on-press with no feedback does not count; the user must SEE the option and get feedback when text is copied.

## Report back
Per fix: commit hash, root cause in plain words, files changed, behaviour now, tests added. Flag anything inspection-only.
