# Rivet fix round

Base e3d5f620, the fetched batch/surface-fix head. Land focused changes on fix-round in section order.

## Surface direction

Use the Workbench palette in Theme.kt. Light canvas #F4F5F3, paper #FBFCFA, ink #182321, signal #225F6B. Dark canvas #1A2020, paper #212A29, ink #F0F5F3, signal #91CBD2. Reuse existing type roles, thin glyphs and sheets. Give the live browser page the emphasis, with a quiet state label beside the page title. The attach sheet places [Camera] [Photos] [Files] above the clipboard row. Put the jump chip above the composer and images at their references. Reviewing the direction against the brief, the page and image already carry the visual detail, so skip decorative frames and animated progress overlays.

## Build sequence

1. HistoryScreen loads the adaptive launcher foreground raster as a flat image. Inspect the tracked raster and launcher layers and use the highest fidelity source at the correct density. Preserve the sidebar header.
2. BrowserRuntime owns one BrowserSession. Sample the current WebView at preview size and observe the same session from chat. Use tool lifecycle for progress, clear on stop or close, and open the existing browser route. Preserve renderer ownership and all browser policies.
3. SettingsScreen shares UtilityColumns. Replace row-coupled wide layouts with independent stacks and inspect the callers. Preserve portrait order.
4. receiveSharedContent copies to app storage but switches occupied sessions. Reuse the copy helper for picker staging in the current composer. Capture session identity before launching a picker, preserve draft text, and leave external share-in routing alone.
5. MessageRow gates SelectionContainer with a custom gesture and Done button. Use the default selection machinery, with a transcript-level boundary for outside taps, and keep composer selection native.
6. CodingSessions.compact already keeps canonical events and past compactions while replacing active_events. Index the canonical sources in SQLite FTS, including old sessions. Return numbered windows, gate recall before the answer, and make the same search available as a tool. Add bounded breadcrumbs, pin recalled evidence in the request tail and use a deterministic unavailable result.
7. ContextPreparation has independent force and automatic entry conditions. Check retained turn grouping and the target along with manual/auto callbacks. Use one projection path and publish in-flight state for both.
8. The idle meter uses previous request usage plus a delta. Use the next request envelope and the same accounting as request preparation, including tools and archive tail.
9. Trace Termux callback, bundle parser and fallback. Test the actual result envelope and distinguish a missing result from an executed command's exit.
10. Follow StarterBrief into provider streaming. Handle DSML at the stream/response seam, including a reply that starts with a split tool envelope.
11. Route the SlashMenu built-ins and typed commands through one parser/dispatcher before unknown-command handling.
12. Reuse ChatScreen list and follow state for the jump chip. Hide at the bottom and preserve a reader's scroll until tapped.
13. Reuse HistoryScreen's recent rows, selection and relative times in the scrollable landscape sidebar.
14. Require positive workspace resolution before applying the inline code token's link style or action.
15. The view model prefers projectGoal over sessionGoal. Gate the pinned row on conversation ownership while keeping the existing project context available.
16. Remove loose pricing from ChangeSummary and omit the missing-price fallback from recovery.
17. Phone access uses UtilityLead for Share to Rivet. Render share availability as information.
18. Trace the heading paint path through BrowserSession configuration and reader/diet rules. Use a fixture where feasible and record what needs the device WebView.
19. Extend Markdown image blocks for relative project image syntax. Reuse workspace resolution and split preview, show plain text for missing files, leave deliverable cards in place.

Run script tests and available JVM tests. Prove JiTTests on the base and branch where runnable. Record Android-only validation in the report. Stage named files so the pre-existing image deletions stay local. Commit the report and push fix-round.
