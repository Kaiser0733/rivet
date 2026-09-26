# Rivet Master Roadmap

Eight phases. Each phase lands working, buildable software; none leaves
the repository in a state that does not compile.

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

## Phase 8 — Release Candidate Engineering (in progress)

Source and CI review found and fixed concrete 0.8.1 release risks. The 0.8.1
debug RC then passed physical Android acceptance, including in-place upgrade,
project and session persistence, approved and denied mutations, commands,
partial-failure recovery, and Undo. Version 0.8.2 retires unreachable
presentation and obsolete API residue and prepares for independent verification
and a short physical regression test. Production signing remains gated by the
permanent certificate pin; no public release has been published.
