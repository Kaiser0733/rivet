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
literal search, and exact-context patching. No model workspace access or runtime.
Implementation is covered by JVM logic and simulated DocumentsProvider tests;
physical-device/provider compatibility validation remains required.

## Phase 4 — Agent Loop (complete)

Provider-neutral coding-agent conversation with native structured calls for all
supported transports, bounded workspace tools, mutation approvals, cancellation,
streaming activity UI, and migrated persistent transcripts.

## Phase 5 — Embedded Runtime

On-device command execution for the agent, terminal UI, runtime
installation and management. License review of any Termux-derived or
GPL-adjacent components happens before a single line is incorporated.

## Phase 6 — Coding-Agent Features

Diff viewing and application, Git integration, and higher-level coding workflows.

## Phase 7 — Android Product Hardening

Performance, accessibility, keyboard/mouse/pointer input polish,
large-screen refinements, error surfaces.

## Phase 8 — Release Candidate

Final hardening, release documentation, distribution readiness.
