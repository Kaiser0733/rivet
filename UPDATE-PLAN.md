# Rivet stability update — execution plan (agreed shape 2026-11-01)

Goal: ship the fixes from `UPDATE-FIX-LIST.md` before real users hit the bugs and uninstall. Kaiser supplies approvals + his phone; Hermes orchestrates; the dsh worker session implements.

## Route (kaiser's build orders, 2026-11-01 — supersede the earlier draft)
- **Backend: agy (Gemini 4 Argon, medium) + the dsh harness worker (DeepSeek V4.1 Flash, medium). UI: the dsh harness worker.** ENI is NOT used for the build (audit only — kaiser's call: the big contenders were sitting unused). Hermes orchestrates, writes every work order, and reviews every diff against the audit before it counts as done; Hermes does not hand-write build code.
- Quota limits WILL be hit repeatedly — expected, not an error. The loop is: worker stops clean → Hermes says "continue" after the reset → worker resumes. This loop is Hermes' job; kaiser is not pinged for it.
- **One branch: `stability-v1.1.0`. NOTHING merges to main until every phase is complete and fully tested.** Workers commit locally per fix; no pushes until the APK step (kaiser's "give me apk" is the push authorisation for this branch when the time comes).
- Build: the VM has no JDK/Android SDK — the APK comes from GitHub Actions off the branch. **Kaiser device-tests the APK first; only after his pass do we merge to main or fix-and-rebuild.**
- Scope split (Hermes' call, put to kaiser 2026-11-01): the alive-signal ticker (F26's minimal fix) ships inside v1.1.0 as part of stability; the FULL dsh-style alive UI is the follow-up release. Cluster D in this branch covers F26 + thinking visibility per bug #3's spec, F27, F14 and the annoyances sweep.
- The new mascot app icon (his standing call) goes in this release too.

## Order
1. **Phase 1 first** (F01 deadline, F02 finish-signal, F05 keep partial text, F08/F09 error vocabulary, F22 foreground service) — the five that remove most user pain.
2. **Phase 2 + 3** next (caps F03/F04/F06/F10, sync tolerance F11/F12/F15/F18, settings safety F28–F31, lifecycle F23/F25).
3. **POTENTIAL findings**: fix defensively where the fix is cheap and safe (F28–F31, F07); device-confirm F20/F24 on a throwaway project on kaiser's phone before relying on them.
4. **Phase 4** as time allows before release; anything unfinished moves to 1.1.1, it does not block the train.

## Gates (non-negotiable, learned the hard way)
- CI green on every step; a worker commit alone never counts as done.
- **Real-device acceptance test on kaiser's phone before release** — replay the exact session that broke: the single-file Three.js game task, start to finish. Pass = no hang on the big write, backgrounding mid-run survives, the >1 MB write completes or continues in parts, thinking/elapsed indicator visibly ticking, a project containing a symlink still syncs.
- Release notes written plain, no numbers, no AI-sounding copy (standing copy law).

## Parallel track
Freeline hackathon endgame runs on its own calendar (freeze 2 Nov, submit 4 Nov) and does not block this — worker sessions are separate, kaiser's attention is the only shared resource, so approvals get batched.
