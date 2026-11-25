# F1 Secrets — report

Branch: `flaw-secrets`, based on post-merge `batch/surface-fix` @ `6f9f78ad`.
Code commits (branch head before this report commit: `7909c233`):

- `0fcc54a7` — Keep GitHub credentials out of shell environments
- `7c82acb9` — Gate project memory writes through the exact diff
- `7909c233` — Key skill script approvals to exact content

The plan is committed as `plan-f1.md` in the first commit.

## Job 0 — merge model-limits-detect

`batch/surface-fix` merged `model-limits-detect` with `--no-ff`, no conflicts.
The resulting batch head was `6f9f78adaf462b8daef3e539c7375084f36bd134`; the
merge was pushed as `0729dabc..6f9f78ad`. `origin/model-limits-detect` was an
ancestor of the batch head, no unique commits remained, and the merged branch
was deleted locally and on origin.

## H2-2.1 / H2-2.2 — credential environment and live redaction

Root cause: `Toolchain.gitEnvironment` put
`GIT_CONFIG_VALUE_0=store --file=<filesDir>/toolchain/git-credentials` into every
command environment. `RuntimeController` refreshed the credential file for every
shell, background process, and terminal. Separately, `AgentContext.redact` was
only applied during compaction; live tool results and the provider request used
the unredacted strings.

Changed:

- `Toolchain.commandEnvironment`/`gitEnvironment` no longer take or emit a
  credential file. Generic shell, background and terminal environments contain no
  GitHub credential path or value.
- `GitCommandAuth` recognises agent `git push/pull/fetch/clone/ls-remote`
  commands and prefixes only that command with a store-helper config. The helper
  file is written for that command and removed afterwards; connecting no longer
  leaves a credential file on disk.
- `GitHubTokenStore` validates a refresh and then wipes it; `clearCredentialFile`
  removes the short-lived file after foreground and background git commands.
- `SecretStore.sensitiveValues()` enumerates decryptable provider keys, the
  GitHub token, and OAuth access/refresh/id tokens.
- `SecretRedactor` combines the existing generic redaction with those literal
  values. `AgentLoop` redacts tool result content/summary before the tool message
  is appended or persisted, and `ContextPreparation` redacts every provider
  request and summary. Worker loops and requests receive the same values.
- Settings copy now says agent-run git pushes use the token.

Tests: `GitCommandAuthTest`, `SecretRedactionTest`,
`GitHubTokenStoreTest` (including no file after connect and file removal after a
refresh), updated `GitToolchainTest`/`CaBundleToolchainTest`, and
`SecretStoreSensitiveValuesTest`.

## H2-1.5 — memory approval gate

Root cause: `GoalMutationGuard` reserved only `.rivet/goal.md`.
`write_file`, `apply_patch`, `create_file`, `delete_path`, `rename_path`,
`move_path`, and `download_file` could rewrite `.rivet/memory.md` directly.
`remember` requested approval but its detail was only the fact text.

Changed:

- `GoalMutationGuard` now reserves `.rivet/memory.md`, including rename/move
  targets. `AgentToolExecutor` returns `reserved_memory` for those calls.
- `remember` reads the current notebook, builds the same append preview the real
  writer will use, and puts the exact line diff in the approval detail.
  Batched memory proposals keep that diff and append the fact list.
- `MemoryFormat.diff` is a bounded pure line diff used by the gate.

Tests: `GoalMutationGuardTest` (direct write/rename/move refused),
`GoalMemoryTest` (new and appended diffs), and `AgentToolExecutorTest`
(`reserved_memory`, approval detail contains `+ - <fact>` and not the untouched
old line).

## H2-4.1 — skill approvals by content, including YOLO

Root cause: `SkillScriptApprovalStore` stored skill names and
`SkillScriptMatcher.isGateNeeded` bypassed the gate entirely in `AutonomyMode.Yolo`.
Replacing a skill's script under the same name inherited the old grant, and YOLO
never asked.

Changed:

- `SkillScriptHit` carries a SHA-256 `contentHash`; approval keys are
  `skillName:hash`. Unreadable scripts are never treated as approved.
- `SkillScriptContent` resolves the referenced skill file/directory through the
  workspace or an absolute file and hashes the exact bytes (bounded to 128 files
  and 4 MiB; unreadable/oversized falls back to asking again).
- `AgentToolExecutor` hashes matched scripts while preparing `run_command`.
- `SkillScriptApprovalStore` stores approval keys, and `AgentToolExecutor`/the
  loop record keys. The gate asks whenever the current key is missing, including
  YOLO; ordinary YOLO command approval is unchanged.
- Existing gate tests were updated for the no-exception rule.

Tests: `SkillScriptContentTest` (one-character change changes the key; unreadable
scripts do not reuse an approval), `SkillScriptGateTest` (YOLO still asks),
`SkillScriptMatchTest`, `SkillScriptApprovalStoreTest`, and the executor hash test.

## Verification actually run

Manual Robolectric/JUnit harness (Gradle unit-test dependencies are not available
offline):

- `:app:compileDebugKotlin` — `BUILD SUCCESSFUL` on final HEAD.
- Focused F1 + model-limit suite: **100 tests, 0 failures**. Included
  `ModelLimitLoopTest`, `AgentToolExecutorTest`, `SkillScriptGateTest`,
  `SkillScriptContentTest`, `SkillScriptApprovalStoreTest`, `GoalMemoryTest`,
  `GoalMutationGuardTest`, `SecretRedactionTest`, `SecretStoreSensitiveValuesTest`,
  `GitCommandAuthTest`, `GitToolchainTest`, `CaBundleToolchainTest`, and
  `GitHubTokenStoreTest`.
- `ChatViewModelTest`: 69 tests, 14 failures. Every failure is the known
  harness-only 5s timeout on this machine. The same 14 test names fail on parent
  `6f9f78ad` (parent: 69 tests / 14 failures); there are no new names or failure
  kinds.
- Parent-red at `6f9f78ad`, same harness:
  - `ParentMemoryRedTest` — 2/2 fail on parent (direct memory write allowed;
    approval detail has no diff).
  - `ParentSkillGateRedTest` — fails on parent (YOLO returns `None`).
  - `ParentTokenEnvRedTest` — fails on parent (credential path is in the env).
  - `SecretRedactionTest` and `SkillScriptContentTest` do not compile on parent
    because `secretValues`, `SecretRedactor`, `contentHash`, `approvalKey`,
    `SkillScriptContent`, and `isApproved` do not exist there. The full compile
    errors are in `.f1-parent/.parent-compile-red.out`.

## Honest gaps

- The credential file is still an app-private file while a git helper is
  running. The shared environment never names it and it is deleted afterwards,
  but a same-UID shell command that deliberately walks Rivet's private files
  during that window could still reach it. A Keystore-backed helper process would
  be needed to close that residual path.
- The terminal no longer gets automatic GitHub auth. Only agent-run git network
  commands receive the short-lived helper; the Settings copy reflects that.
- Skill directory hashing is bounded. An oversized or unreadable script cannot
  produce a stable approval key, so it asks again rather than inheriting a grant.
- `remember` still follows the existing YOLO product decision (ordinary YOLO
  removes its approval); direct file-tool writes to memory are refused in every
  mode.
- Full Gradle unit tests were not run because the offline environment cannot
  resolve the test dependencies. CI remains the final gate.
