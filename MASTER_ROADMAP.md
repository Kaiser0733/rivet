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

## Phase 3 — Project Workspace

Selecting a project on device, filesystem browsing, file viewing and
editing.

## Phase 4 — Agent Loop

Coding-agent conversation over the selected provider, streaming chat UI,
session persistence.

## Phase 5 — Embedded Runtime

On-device command execution for the agent, terminal UI, runtime
installation and management. License review of any Termux-derived or
GPL-adjacent components happens before a single line is incorporated.

## Phase 6 — Coding-Agent Features

Tool execution with approval controls, diff viewing and application,
Git integration.

## Phase 7 — Android Product Hardening

Performance, accessibility, keyboard/mouse/pointer input polish,
large-screen refinements, error surfaces.

## Phase 8 — Release Candidate

Final hardening, release documentation, distribution readiness.
