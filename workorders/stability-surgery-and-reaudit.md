# Stability surgery + re-audit — kaiser's order 2026-11-02

Kaiser's words: "if there clearly bugs then fix it even it needs surgery.
Bugs are bugs and this makes app a bad experience... fix everything without
creating bugs and do full audit of app when it becomes stable then give me
app. I don't want to install app with bugs multiple times... Give app then
its fully stable. Do full deep audit multiple times."

So: NO new APK until this order completes. The two structural items that
were flagged-not-built are now IN SCOPE, surgery authorised. End state:
audits stop finding proven bugs, CI green, then Hermes builds one final APK.

Work in phases, IN ORDER. Append progress to
`workorders/stability-audit-2.md` as you go (write it before Phase S code).
One commit per fix. No test weakened or deleted to pass. No push.

## Phase S — the two structural fixes

S1. **A turn must never write into the wrong conversation.** Today a turn
persists through the storage layer's global active-session pointer, so a
send landing inside a changeSession window can file conversation A's
transcript into B. Make the turn's writes session-bound end to end (the
`expectedSessionId` pattern compact already uses is the model). Prove it
with a test that interleaves a switch with an in-flight turn and shows A's
transcript intact and B untouched.

S2. **Undo must survive a conversation switch.** The queued/applied switch
rebuilds ChatUiState from scratch and discards the Undo registration made
earlier in the same turn; nothing rehydrates it from the on-disk
checkpoint. Fix it (rehydrate on load, or carry across when the workspace
matches — your call, justify it) and pin it with a test.

## Phase F — every remaining flagged item from both audits

F1. 401/403/5xx responses drop the provider's body — surface its words in
    the banner instead of a bare fixed sentence.
F2. A non-SSE 200 response is read as a truncation — handle it as the
    response it is, or fail with copy that names the real problem.
F3. The effort classifier can replace a real provider message — tighten it
    so a message that is not an effort refusal reaches the user verbatim.
F4. `capacitySource` never gates the planning target — when the window is
    the 256K fallback guess rather than known/detected, compaction must
    not plan as if the guess were fact. Bound the change; tests must show
    known/detected windows are unaffected.
F5. Title dead-end: one transient title failure permanently leaves a chat
    titled "New session". Re-judge against the "runs once" intent and fix
    it WITHOUT burning a title call on failing turns (e.g. a failure keeps
    the marker pending so the next SUCCESSFUL turn titles it; a bad/empty
    title response still ends it).
F6. Per-delta rendering cost (from round 4): the reasoning chain is
    re-published and re-scanned per delta. Implement the cure you named:
    incremental preview + bucketed scroll key. No behaviour change.
F7. The three SUSPECTED UI items from the full audit — settle each with
    the observation the audit doc names; fix if proven, close with
    evidence if not.

## Phase A2 — second full audit (this is the "multiple times" part)

Only after S + F are committed: audit the ENTIRE branch again
(`c0c49c8..HEAD`), fresh eyes, same evidence bar — the surgery code gets
the hardest look, since new code is where new bugs live. Findings doc
continues in `stability-audit-2.md`. Fix proven bugs (one commit each),
flag nothing silently. If this pass finds ZERO proven bugs, say so
plainly — that is the stability signal Hermes is waiting for.

## Rules (unchanged)

Skills first. Diagnosis before code for anything non-obvious. Every claim
carries file:line or a commit hash, or is marked unproven with the
evidence that would settle it. Copy law applies. Report at the end:
phases done, commits, the A2 verdict (proven bugs found / none), and what
remains genuinely unproven.
