# Rivet Master Roadmap

Ten phases. Each phase lands working, buildable software; none leaves the
repository in a state that does not compile.

## Phase 1 — Foundation (complete)

Android application shell: Gradle build, CI, release signing scheme,
versioning rules, navigation frame with empty states for the four future
modules, documentation set. No feature systems.

## Phase 2 — Provider Engine (complete)

Multiple LLM providers, custom OpenAI-compatible providers, model
fetch/selection, mid-session switching, credential storage, streaming
text chat with cancellation, persistent configuration and one persistent
conversation.

## Phase 3 — Project Workspace (complete)

Native SAF selection with persisted tree permission, nested browsing, bounded
UTF-8 reading/editing, fingerprint conflicts, native create/delete/rename/move,
literal search, and exact-context patching. Later phases added agent access and
runtime synchronization through the same selected project boundary.

## Phase 4 — Agent Loop (complete)

Provider-neutral coding-agent conversation with native structured calls for all
supported transports, bounded workspace tools, mutation approvals, cancellation,
streaming activity UI, and migrated persistent transcripts.

## Phase 5 — Embedded Runtime (complete)

On-device command execution, an interactive terminal prototype, and a
conflict-safe private POSIX mirror of the SAF workspace. Termux-derived
components were reviewed for incorporation. The interactive Terminal surface
was retired after the Phase 7 Chat-first decision; the command runner remains.

## Phase 6 — Coding-Agent Features (complete)

Diff review, read-only Git inspection, and pre-turn checkpoints for Undo. The
standalone Changes surface was retired in Phase 7; contextual changes remain in Chat.

## Phase 7 — Chat-First Product Simplification (complete)

Chat and Settings are the only normal surfaces. Project choice, approvals,
activity, results, and Undo are contextual in Chat. Runtime, files, Git, and
checkpoint systems remain agent infrastructure rather than user destinations.

## Phase 8 — Release Candidate Engineering (complete)

Source and CI review fixed concrete release risks. The 0.8.1 debug RC passed
physical Android acceptance, including in-place upgrade, project and session
persistence, approved and denied mutations, commands, partial-failure recovery,
and Undo. Version 0.8.2 retired unreachable presentation and obsolete API
residue. Production signing remains gated by the permanent certificate pin;
no public release has been published.

## Phase 9 — Harness Core (complete)

Provider request budgeting, old tool-output pruning, bounded task state,
validated active-context reconstruction, compaction diagnostics, and
no-progress detection strengthen long coding turns. Full session events,
approval, workspace, and runtime boundaries remain in place. The 0.9.1
backend passed the preceding physical acceptance process.

## Phase 10 — Rivet UI/UX Redesign (in progress)

Chat remains the home surface, with full-screen History and Settings routes.
The rose-and-ink presentation uses the clean heart source as a Chat-only
decorative background; other routes stay on the plain rose surface. History
pinning is persisted in SQLite; provider editing and the accepted agent
workflows remain intact.
