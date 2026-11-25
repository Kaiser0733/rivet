# Rivet browser round 5

Branch `browser-round5` starts at `0729dabcfb01b052c77ec6d5af3e0a82aab8a9f3` on `origin/batch/surface-fix`. The base has no root `AGENTS.md`. I used the Rivet instructions in the companion workspace.

## 1. Keyboard inside a page

Commit `63a252c1c46c1a59420e6ee03ba566a20b454107`.

**Root cause.** `BrowserHost` called the address bar’s focus-clearing and keyboard-hide action on page touches and again when the WebView gained focus. That gave Compose a chance to dismiss the IME during the native WebView input handoff. The browser also omitted the IME inset in the edge-to-edge activity.

**Fix.** Page touch releases the address bar’s Compose focus, gives the WebView native focus in touch mode, and passes the event through to it. The browser viewport resizes with the keyboard, and the address bar still dismisses its keyboard on Go and toolbar actions.

**Files.** `ui/browser/BrowserHost.kt`, `ui/browser/BrowserScreen.kt`, `BrowserUrlFieldUiTest.kt`, `BrowserSourceSafetyTest.kt`.

**JiTTest.** `BrowserUrlFieldUiTest.pageTouchesLeaveKeyboardControlToTheWebView` checks the keyboard-hide calls during the handoff, including a second tap.

## 2. Start page and address-bar search

Commit `ea45cfba35735926961ac6e6a7fbb74de465540f`.

**Root cause.** `userOpen` skipped navigation when the session URL was blank, and the address bar passed all input to the strict visual URL validator. The URI keyboard also made multiple-word input harder.

**Fix.** A browser session with no page opens `https://www.google.com/`. The address bar resolves full URLs and bare hosts, and encodes other input as a Google query. The Go keyboard allows spaces. Existing URL security checks still apply. An explicitly opened new tab keeps its blank starting state.

**Files.** `browser/BrowserSession.kt`, `browser/BrowserUrlPolicy.kt`, `ui/browser/BrowserScreen.kt`, `BrowserAddressBarTest.kt`, `BrowserStatusTest.kt`.

**JiTTests.** `BrowserStatusTest.openingAnEmptyBrowserStartsAtGoogle` checks the normal opening path. `BrowserAddressBarTest` checks URL, loopback, and query handling.

## 3. Reader transitions and renderer recovery

Commit `70069564b8548edabe93a8a11d84b98c64c4eb2a`.

**Root cause.** The code matches the reported race. Leaving Reader armed `verifyReaderPaint` while a new load could already be under way on the parked WebView. The sample could compare the new page DOM with the old frame. Release, no-view teardown, and teardown finalization could all call `terminateAll`. A spontaneous renderer exit requested teardown without scheduling recovery.

**Fix.** Reader releases its WebView and Page view loads on a fresh one. Main-frame navigation invalidates pending paint work. Paint recovery and renderer exit share one restart path tied to the departing renderer generation and active tab. Teardown waits for that view’s exit or destruction before allowing replacement. The error banner offers Reload, which retries the current page with a fresh renderer when needed.

**Files.** `browser/BrowserSession.kt`, `browser/RendererTerminationPolicy.kt`, `ui/browser/BrowserHost.kt`, `ui/browser/BrowserScreen.kt`, `BrowserSessionReaderCloseTest.kt`, `BrowserSessionTeardownTest.kt`, `BrowserSourceSafetyTest.kt`, `BrowserUrlFieldUiTest.kt`, `RendererTerminationPolicyTest.kt`.

**JiTTests.** `BrowserSessionTeardownTest` covers stale release against a replacement, teardown completion, navigation during a scheduled paint check, and retry after a second renderer failure. `BrowserSessionReaderCloseTest` covers reloading a surviving tab and deferring its restore until the replacement finishes. The four Compose layout tests check visibility of Reload and Close browser at phone and tablet sizes in both orientations.

## 4. Reader HTTP and renderer status

Commit `26c070faf1ae4cd55a7c51e8e30b9097bbb66769`.

**Root cause.** The code matches both reported failures. The agent’s remote open path starts with OkHttp’s Reader request, which carried no browser User-Agent and described its 403 as a page failure. Status inferred a healthy Reader from a retained URL after the renderer had died.

**Fix.** Reader sends the browser’s Chrome User-Agent, including on redirects. Reader HTTP failures use `browser_reader_http`, successful Reader responses use `reader_http_status`, and visual responses use `http_status`. A renderer exit reports `mode=gone`, `alive=false`, `healthy=false`, `error_kind=browser_renderer_unavailable`, and a `browser_reset(reopen=true)` hint. A failed Reader fetch preserves the active page.

**Files.** `agent/AgentToolError.kt`, `browser/BrowserSession.kt`, `browser/BrowserTools.kt`, `browser/ReaderService.kt`, `BrowserSourceSafetyTest.kt`, `BrowserStatusTest.kt`, `ReaderServiceHttpTest.kt`.

**JiTTests.** `ReaderServiceHttpTest` covers the header on the first request and redirect, and the error kind for a Reader 403. `BrowserStatusTest.aGoneRendererStaysGoneAfterReleaseAndHasAResetHint` checks the tool JSON after release. A direct GET to Wikipedia’s Google article returned **403** with the OkHttp 4.12 User-Agent and **200** with Rivet’s Chrome User-Agent.

## Local verification

The standalone Kotlin/JUnit run passed **11 of 11** address policy, Reader HTTP, and renderer termination tests. Both Reader HTTP tests failed against the base because it sent OkHttp’s User-Agent and returned `browser_http` for a Reader 403. The terminal emulator’s `ByteQueueTest` passed **2 of 2** in a standalone JUnit run.

## Honest gaps

ADB showed no physical device. The VM has no KVM support, and the emulator stayed offline after forced software startup. Native keyboard entry, real Chromium process recovery, and on-device appearance in portrait and landscape at phone and tablet sizes still need device verification.
