# Full audit — stability-v1.1.0 (kaiser ordered 2026-11-01 night, via Hermes)

Scope: the ENTIRE branch diff `c0c49c8..HEAD` (all of v1.1.0: clusters A–E,
device-fix rounds 2, 3 and 4, and the CI-fix commits), plus the code those
changes touch. Bugs hb created AND pre-existing bugs sitting in the touched
areas are both in scope.

## Method (skills first: systematic-debugging, receipts, verification-before-completion)

1. Write the audit doc FIRST: `workorders/full-audit-2026-11-01.md`. Every
   finding gets: severity, file:line evidence, and PROVEN or SUSPECTED.
   Suspected = named evidence that would settle it. No guessing — an
   unproven claim stated as fact fails this audit.
2. Hunt, at minimum:
   - logic errors and off-by-ones in every changed hunk
   - races and state leaks across turns and sessions (flows, jobs, the
     queued session switch, generation guards)
   - error paths that swallow, misreport, or strand the user
   - persistence gaps: written in one place, read from another, lost on restart
   - UI state: keyed remember/rememberSaveable misuse, stale previews
   - dead code or dead API this branch introduced
   - copy-law violations in user-facing strings
3. Cross-check the claims: re-verify that UPDATE-FIX-LIST.md / the ENI audit
   findings marked fixed in this branch are ACTUALLY fixed in the code as it
   stands at HEAD. A finding marked fixed that is not = a bug.
4. Re-judge the four open items hb flagged in round 4 (MAX_ERROR_BODY_BYTES
   unit mismatch; capacitySource never gating the planning target;
   PreviewWindow.reset() with no production caller; the title dead-end after
   one failed title request): fix if proven AND small, else flag with cost.
5. Fixes: PROVEN bugs only, one commit per fix, smallest change that fixes
   it, no behaviour change beyond the fix, no test weakened or deleted to
   make something pass. Add a test that fails without the fix where the
   harness supports one. If a fix would need an architecture change, STOP —
   flag it with cost instead.
6. Do not push. Report at the end: findings table (fixed / flagged /
   cleared-as-clean), commits, and what remains genuinely unproven.
