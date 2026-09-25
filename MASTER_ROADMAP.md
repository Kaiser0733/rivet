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

On-device command execution, an interactive terminal, and a conflict-safe
private POSIX mirror of the SAF workspace. Runtime packages build on this
execution substrate after physical validation. Termux-derived components
receive a file-level license review before incorporation.

## Phase 6 — Coding-Agent Features (complete)

Diff viewing and application, Git integration, and higher-level coding workflows.

## Phase 7 — Chat-First Product Simplification (complete)

Chat and Settings are the only normal surfaces. Project choice, approvals,
activity, results, and Undo are contextual in Chat. Runtime, files, Git, and
checkpoint systems remain agent infrastructure rather than user destinations.

## Phase 8 — Release Candidate Engineering (in progress)

Audit the accepted product, fix concrete release risks, verify upgrade and
signing paths, and prepare a candidate for independent review and physical
acceptance. This does not publish a release.
