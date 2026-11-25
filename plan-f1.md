# F1 Secrets — plan

Branch `flaw-secrets` from post-merge `batch/surface-fix` @ `6f9f78ad`.

## 1. GitHub token environment + live redaction (H2-2.1, H2-2.2)

Root cause: `Toolchain.gitEnvironment` adds `GIT_CONFIG_VALUE_0=store --file=<filesDir>/toolchain/git-credentials`
to every command environment; `RuntimeController` refreshes and passes that file for commands,
background processes, and the terminal. The credential file is readable by the same process UID
as the shell. Separately, `AgentContext.redact` is only called by compaction pruning; live tool
results and the provider request carry secrets.

Change:
- Remove the credential-file argument from `Toolchain.commandEnvironment` / `gitEnvironment`.
  Generic shell, background, and terminal environments never contain a credential path or value.
- For a recognised agent `git` network command (`git push/pull/fetch/clone/ls-remote`), prefix the
  command with `-c credential.helper=store --file=<app-private file>`. The token path is not in the
  shell environment (nor in generic commands), and the credential is only resolved for that git
  invocation. The terminal keeps its normal user environment.
- Add `SecretStore.sensitiveValues()` (data-driven: every decryptable provider/GitHub slot plus
  OAuth token fields).
- Add `SecretRedactor` and pass the values into `AgentLoop` and `ContextPreparation`. Redact live
  tool result `content`/`summary` before appending, and redact request messages before the provider
  call.
- Tests: `GitCommandAuthTest` (env has no path; git prefix has helper; non-git command unchanged),
  `SecretRedactionTest` (literal token masked in a live tool result and request), plus updated
  `GitToolchainTest` and a `SecretStore.sensitiveValues` test.

## 2. Project memory write guard (H2-1.5)

Root cause: `GoalMutationGuard` reserves only `.rivet/goal.md`; `write_file`, `apply_patch`,
`create_file`, `delete_path`, `rename_path`, `move_path`, and `download_file` can rewrite
`.rivet/memory.md`. `remember` is the intended writer but file tools are unguarded.

Change:
- Add `MemoryFormat.PATH` to the reserved-mutation guard; return `reserved_memory` with a clear
  tool-error action. `remember` remains the only writer.
- Compute the proposed memory content in `AgentToolExecutor` while preparing `remember`, and put
  the exact line diff in the approval detail (plus the fact list for batched proposals).
- Add `MemoryFormat.diff(before, after)` (pure, bounded) and test a refused direct write, a refused
  unapproved/unwritten memory result, and an approved write whose approval detail contains the added
  line.

## 3. Skill approvals by content hash, no YOLO exception (H2-4.1)

Root cause: `SkillScriptApprovalStore` stores skill names; `SkillScriptMatcher.isGateNeeded`
explicitly disables the gate in YOLO. Replacing a skill's scripts under the same name inherits the
old grant, and YOLO never asks at all.

Change:
- `SkillScriptHit` carries a content hash. `AgentToolExecutor` resolves the referenced script (or
  skill directory) through the workspace/absolute file and hashes the exact bytes; unreadable
  scripts get no reusable approval.
- Approval keys are `skillName + ":" + contentHash`; the store and in-turn set hold those keys.
- `skillScriptGate` asks whenever a matched hit has no current key, including YOLO. The ordinary
  command approval remains the YOLO path; only the skill-content grant is not skipped.
- Tests: approve a hash, change one character, re-gate asks; YOLO asks on an unapproved script;
  store round-trips hash keys; existing gate tests updated to the no-YOLO-exception rule.
