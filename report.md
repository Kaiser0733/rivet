# browser-round5 gate-fix

Base: `batch/surface-fix` @ `0729dabc`.

## 1. `BrowserEvalSessionTest.evalRefusesAnIdentityHandoff` · `c530e6f2`

**Root cause:** `openText()` correctly records an identity handoff, but leaves `activeUrl` blank. `eval()` checked `activeUrl.isBlank()` first, so a handoff was reported as `browser_not_open` instead of `browser_handoff`.

**Fix:** check the handoff state before requiring a page URL. This is production-code behaviour; the existing red test is the JiT case.

`BrowserEvalSessionTest` passes, including the timeout/origin cases.

## 2. `BrowserKeyModalTest.navigationClearsTheModalBlock` · `8c467f16`

**Root cause:** not a production bug. The branch's `loadForInteraction` now correctly waits for an in-flight page load. The test simulated `onPageStarted` (which clears the modal) but never `onPageFinished`, then expected a key action on a page that was still loading.

**Fix:** complete the simulated navigation in the fixture before pressing the key. The test still proves the modal block is gone and the key is dispatched after navigation.

`BrowserKeyModalTest` passes.

## 3. `BrowserSessionReaderCloseTest.closingActiveTabInReaderModeLoadsSurvivingPageOnANewRenderer` · `e7349956`

**Root cause:** after the reader renderer was released, closing the active tab took the `userOpen()` path, which built a new hidden WebView and loaded the surviving URL there. The test's replacement renderer therefore never received a `loadUrl`, and the surviving page was not loaded on the view the host attaches.

**Fix:** when the old renderer is gone or tearing down, record the surviving URL and defer the load until `attach()` receives the replacement renderer. `attach()` then loads once, and the existing `onPageFinished` path performs the deferred snapshot restore on that same renderer.

`BrowserSessionReaderCloseTest` passes, including the assertion that snapshot restore JS waits for `onPageFinished`.

## Test results

- Branch production compile: **BUILD SUCCESSFUL**.
- `BrowserEvalSessionTest`, `BrowserKeyModalTest`, `BrowserSessionReaderCloseTest`: **7/7 pass** in the local JUnit/Robolectric harness.
- Full Gradle test suite was not run in this sandbox; CI remains the gate.

## Honest gaps

- No production change was made for the modal test; the test contradicted the branch's intended wait-for-navigation behaviour.
- The surrounding browser package was not exhaustively run; the three named classes account for the gate failures.
- No APK, release, version, self-updater, or other branch changes.
