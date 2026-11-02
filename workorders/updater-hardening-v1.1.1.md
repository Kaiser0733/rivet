# v1.1.1 updater hardening — record

Order: kaiser, 2026-11-02. Branch `v1.1.1-updater`. Public issue: the in-app **check** works
(api.github.com answers, 1.1.0 offered) but the **download** dies with *"Rivet couldn't reach GitHub.
Check your connection and try again."* on two devices, two SIMs, VPN off, healthy internet. The
updater file is byte-identical from v0.11.0 to v1.1.0, and the GitHub chain serves real APK bytes from
other networks.

**The trigger is not proven and was not the target.** Everything below fixes fragility that is proven
from the code. Five items, one commit each.

## A. Bounded retries (`7ab0892`)

Was: `retryOnConnectionFailure(false)` and no retries of its own — one dropped connection ended a
14 MB download.

**Chosen: an app-level loop, OkHttp's connection retry left off.** OkHttp cannot resume a request that
already streamed bytes, cannot be counted or observed from here, and having both on would mean two
owners of retrying with no single place to test. `retrying()` makes 3 attempts with 400 ms / 800 ms
pauses, and retries only `UpdateFailure(retryable = true)`, set in exactly one place: `fetch`'s
`onFailure`. A response that arrived — 404, 500, a bad body, a size mismatch, a validation failure — is
never retried, because it would fail identically three times.

## B. Resume (`c2845ab`)

Was: a failed attempt deleted the partial file; every retry started at zero.

Now, within one `download()` call, a transport failure keeps the partial and the next attempt sends
`Range: bytes=<partial size>-`. A `206` is appended at that offset; a `200` truncates and restarts, so
a stitched file cannot be produced; a `206` whose `Content-Range` does not start at the partial's end
is treated as a restart. The final size check still requires exactly the published size, and
`UpdateApks.verify` is untouched.

## C. Copy (`2739b3b`)

Was: every transport failure on either leg said "Check your connection" — dishonest for a user whose
connection was fine, and silent about which server failed.

Now: check leg — "Rivet couldn't reach GitHub to check for updates. Try again later."; download leg —
"Rivet couldn't download the update file. GitHub's file server could not be reached."; a bad status —
"GitHub answered, but not with the update file." "Check your connection" is gone from the updater
family; it stays in the provider family, where the evidence really can point at the user's settings.

## D. Mirror fallback (`d7d0e1e`)

Was: one road, the release-asset redirect chain.

Now: if that chain spends its retries on transport failures, the same filename is fetched from
`https://vastraalabs.online/downloads/`. The partial from the GitHub attempt is deleted first (two
hosts can serve different bytes; a prefix from one is not a prefix of the other). The result lands in
the same staged file, is held to the same exact published size, and goes through **the same**
`UpdateApks.verify` — signature against the installed signer, package, versionName, versionCode —
before install is offered. The verification code is reused, not duplicated, and no weaker path exists.
The **metadata leg stays GitHub-only**, so a mirror can never announce an update.

## E. HTTP/1.1 — judgement call (`4b2cf58`)

Taken, and labelled **unproven**. The device symptom (API check fine, download dies immediately) is
also the shape of an HTTP/2 stream reset or a middlebox mangling HTTP/2 to a CDN host. Nothing in hand
proves it is this bug, so this is insurance, not a diagnosis. Cost: HTTP/1.1 on one 14 MB transfer, no
user-visible latency. Scope: the update client only — a separate `OkHttpClient` from provider and
project networking, which cannot be affected.

## Version

`versionName 1.1.1`, `versionCode 25` (`4b2cf58`).

## Who this protects, plainly

**Builds up to and including 1.1.0 cannot use any of this.** Their code is frozen: the retries, the
resume, the copy, the fallback and the HTTP/1.1 choice all live in 1.1.1's binary, so a device that
fails to download *1.1.1* on 1.1.0 still fails. This round protects **1.1.1 onward** — every future
update — and it also means the update *to* 1.1.1 itself is the last one that has to succeed on the old
client. Nothing here can rescue a user who cannot fetch 1.1.1 at all; that remains a one-time manual
install or whatever the network allows.

## Follow-up round

**JOB 1 — the red CI, fixed (`1fae1a1`).** Run 41402769275 stopped at `:app:compileDebugKotlin` with two
errors, both from the fallback wrapper: `File.outputStream()` has no `append` parameter (now
`FileOutputStream(target, start > 0)`, so a resumed attempt appends and a restart truncates), and the
`withContext` lambda's type collapsed to `Unit` once the redirect chain was wrapped in `while (true)` —
reported as `Any!` where `File` was expected. Both in one commit because the second is a consequence of
the same wrapper, not a separate slip.

**JOB 3 — secure DNS for the update client (`89f7bc0`).** New evidence: Chrome downloaded the same APK
from GitHub on the device and network where the app fails, and `api.github.com` resolves for the app
while the file host does not, across two carriers. That is a resolver answer, not TCP. The update client
now asks the system resolver first and falls back to DNS-over-HTTPS (`cloudflare-dns.com/dns-query`)
only when a name cannot be resolved at all. **Fallback, not DoH-first**, chosen because a working system
resolver should stay in charge — some networks resolve internal names only — and because DoH-first
would change behaviour for every user to fix a resolver fault that only some see. Scope: this client
only. `okhttp-dnsoverhttps:4.12.0` added to the build, since the core artifact does not carry
`DnsOverHttps`.

## Tests — closed

All six groups from the stop point exist in `app/src/test/java/com/kaiser/rivet/updates/UpdateResilienceTest.kt`:

1. `theMetadataLegRetriesOneDroppedConnection`, `theDownloadLegRetriesOneDroppedConnection` — a
   `DISCONNECT_AT_START` costs one extra request and the call succeeds; the server sees exactly 2.
2. `aDownloadDroppedMidBodyResumesFromTheBytesOnDisk` — a 2-of-4-byte body then a dropped socket; the
   second request carries `Range: bytes=2-`, the `206` with `Content-Range: bytes 2-3/4` completes it,
   and the staged file is exactly the fixture bytes at the published length.
3. `aTwoHundredAnswerToARangeRequestRestartsCleanly` — the retry still offers the Range, a `200` answer
   replaces the file from zero, and the result is the second body, never a stitch.
4. `theMirrorIsTriedOnlyAfterGitHubsRetriesAreSpent` — three requests to the release-asset path, then
   one to `/downloads/Rivet-v0.11.1.apk`. `aMirrorFileOfTheWrongSizeIsStillRefused` — the mirror is
   held to the published size and leaves nothing behind; the file it does produce is the same staged
   file `UpdateApks.verify` inspects, and that verification code is untouched and still covered by
   `UpdateApksTest`.
5. `eachLegNamesTheServerThatFailed` — both transport sentences are pinned exactly after their retries
   are spent, and the assertion refuses any message containing "check your connection".
6. `theBuildDeclaresOneOneOneAsVersionCodeTwentyFive` — reads `build.gradle.kts` and pins 1.1.1 / 25.

**One production bug was exposed by writing these, fixed in its own commit (`f7ce1e3`).** A connection
lost *mid-body* raises `IOException` inside the response callback, and `fetch` turned that into a
non-retryable failure — so the retry loop would not retry the one failure this round exists for and the
partial file was deleted instead of resumed. The proof is tests 2 and 3: they fail on the previous head
and pass with the fix, which makes `IOException` in that handler retryable and changes nothing else.

No other production change came out of this round.

**CI run 41404031158 — 755 tests, 752 passed, 3 failures, all three my tests' fault.** No production
code is implicated; all three were simulations that did not produce the failure they claimed.

1. `aDownloadDroppedMidBodyResumesFromTheBytesOnDisk` and
2. `aTwoHundredAnswerToARangeRequestRestartsCleanly` — both threw `UpdateFailure` from
   `GitHubUpdates.kt:175`, the *size validation* ("The update download was incomplete"), not a transport
   error. Cause: the fixtures sent a 2-byte body with `Content-Length: 4` and a clean
   `DISCONNECT_AT_END`. A clean end-of-body is not a dropped connection — the client reads two bytes,
   sees end-of-stream, and rejects them at the size check, which is non-retryable **by design**. So
   neither test ever created the mid-body drop it was written for, and the retry/resume paths were never
   entered. Rebuilt: `DISCONNECT_DURING_RESPONSE_BODY` drops the socket part-way through the body, and a
   `Dispatcher` answers the retry by reading the `Range` it is given (`206` with a matching
   `Content-Range` for the resume test; a `200` with a different body for the restart test, where a
   stitching client would fail on length or content). The resume test now asserts that the drop happened
   at all (`resumedFrom in 1..3`) before asserting the Range and the final bytes — the assertion it was
   missing is the one that would have caught this the first time.
3. `theMirrorIsTriedOnlyAfterGitHubsRetriesAreSpent` — `AssertionError` on the path list.
   `DISCONNECT_AT_START` drops the socket *before* the request is read, so MockWebServer never records
   it and the captured list came up short. Rebuilt with a `Dispatcher` and
   `DISCONNECT_AFTER_REQUEST`, which records the request and then drops the connection: three
   asset-path requests, then `/downloads/Rivet-v0.11.1.apk`, with the count pinned to four.

Assertions were not weakened: the range test additionally asserts the drop location, and the fallback
test now pins the total attempt count. The other 752 tests, including both retry-leg tests, are
untouched.

**CI finding on the test round (`87aadff` → fixed next).** Run 41403684086: production code compiled and
`okhttp-dnsoverhttps:4.12.0` resolved, so the whole hardening and the DoH fallback are build-clean. The
only failure was test compilation in `UpdateResilienceTest.kt`: `MockWebServer`'s `RecordedRequest` has
no nested `.request` property, so `takeRequest().request.url.encodedPath` could not compile. It exposes
`.path` and `.requestUrl`; the fallback test now reads `.path` and asserts exactly what it did before —
three requests to the release-asset path, then `/downloads/Rivet-v0.11.1.apk`. The other `takeRequest()`
uses in the file read a header, which `RecordedRequest` does expose, so they were already correct. No
production code touched in that round.

**CI run 41404621240 — 755 tests, 4 failed. Progress report, round still open.**

Hermes read the top frame for the three: `GitHubUpdates.kt:266`, the retryable transport failure built in
`fetch`'s `onFailure`. So the transport exception escaped `download()` with the retries exhausted — in
all three, including the two where the dispatcher had a good answer waiting.

**Mechanism found so far, and it is in my tests, not the client.** An exception thrown from inside a
`MockWebServer` `Dispatcher` aborts that connection, and the client reports the abort as *another*
transport failure. My resume dispatcher did exactly that: `ranges.last()!!` throws an NPE whenever the
retry arrives without a `Range` header — which is precisely the case where the previous attempt wrote
zero bytes before the drop. Each attempt then died the same way, three attempts exhausted, and the
transport failure escaped: the simulated drop was hiding its own cause. Fixed in `9e422b1` by making the
dispatcher incapable of throwing: a Range-less request gets the whole file, and an offset is clamped.

**Not yet ruled:** whether `DISCONNECT_DURING_RESPONSE_BODY` leaves the pooled connection unusable for
the immediate retry (the hypothesis Hermes raised), and why `aMirrorFileOfTheWrongSizeIsStillRefused`
regressed from pass to AssertionError at `UpdateResilienceTest.kt:184` after the dispatcher rebuild —
that one changed from `DISCONNECT_AT_START` to `DISCONNECT_AFTER_REQUEST`, so the likely cause is the
policy change rather than timing, but it is not proven. **The round stops here rather than guess again**;
the next pass starts from `9e422b1` and, if the dispatch-throw theory is right, the three should be
green — and if not, the remaining candidate is the pooled-connection hypothesis, whose test is a
dispatcher that answers the *first* Range-bearing request regardless of attempt count.

**Route-fix round — the mechanism, and the predictions this run is testing.**

Diagnosis (Opus), accepted because it explains every run so far including the ones my own fixes did not
move: in the failing tests the drop lands on an **established** connection that never completed an
exchange, so `RealConnection.trackFailure` postpones the `127.0.0.1` route in the client's
`RouteDatabase`. On the CI runner `localhost` resolves to `[127.0.0.1, ::1]`, so `RouteSelector` hands
every later attempt `[::1]` only — where MockWebServer is not listening. Connect refused → `onFailure`
(`GitHubUpdates.kt:266`) → retries exhausted. `DISCONNECT_AT_START` fails during connect/TLS and never
touches the route database, which is why the AT_START tests recovered and mine did not. That also
explains why `9e422b1` (making the dispatcher unable to throw) changed nothing: the dispatcher was never
the problem. And test (d)'s earlier pass was for the wrong reason — `DISCONNECT_AT_START` is honoured
only through `Dispatcher.peek()` at accept time, so under a custom dispatcher it was ignored and an
empty 200 was served, which is where "incomplete" came from.

Fixed in the harness, tests only (`23e3ba9`): the certificate carries `127.0.0.1` as well as
`localhost`, and the interceptor rewrites to `server.url(path).newBuilder().host("127.0.0.1")`, so DNS
yields exactly one route and the route database can only postpone the route that is serving.
`GitHubUpdatesTest` was given the same treatment — it uses `DISCONNECT_AT_END`, the same
post-handshake policy, and passed only because its failures are asserted.

**Predictions, recorded before the run so the run is a real test of the diagnosis:**

1. `aDownloadDroppedMidBodyResumesFromTheBytesOnDisk` — the second request reaches the server with
   `Range: bytes=2-`, and the staged file is exactly `abcd`.
2. `aTwoHundredAnswerToARangeRequestRestartsCleanly` — the staged file is exactly `wxyz`.
3. `theMirrorIsTriedOnlyAfterGitHubsRetriesAreSpent` — the server records three
   `/Kaiser0733/rivet/releases/download/v0.11.1/Rivet-v0.11.1.apk` requests, then one
   `/downloads/Rivet-v0.11.1.apk`.
4. `aMirrorFileOfTheWrongSizeIsStillRefused` — the mirror's 5-byte answer is refused with "incomplete"
   and nothing is left behind.

If 1-4 pass, the diagnosis holds and this round's work is done. If 1 or 2 still reach `:266`, the cause
is not the route database and the next thing to try is a dispatcher that answers the first
Range-bearing request regardless of attempt count — which separates route exhaustion from any remaining
pooled-connection effect. If 3 or 4 fail on the path assertions while 1 and 2 pass, the remaining issue
is in the dispatcher's alternation, not in the client.

**Last-test round — `GitHubUpdatesTest.downloadsAreBoundedEvenWithoutAnHonestLengthAndFailuresCleanTemps`.**

Run 41407185901: 755 tests, exactly one failure — this one. Every `UpdateResilienceTest` test is green, so
the route fix held and Opus's prediction that this test was the collateral was right.

**Ruling: the test was wrong, and it had been passing for the wrong reason twice over.** Two things were
true of its construction. Its sub-cases were paired one enqueued response to one `download()` call, but a
download retries a transport failure three times, so the queue no longer lined up with the cases. And its
`DISCONNECT_AT_END` case — a 1-byte body announcing `Content-Length: 4` — is a **mid-body transport
failure** since `f7ce1e3`, so it consumes three responses; the third landed on the empty queue, and
`QueueDispatcher` holds the request until the 10 s read timeout. That is the ~15 s the run's test task
grew by. Before the route fix this never surfaced because route poisoning failed the sub-case instantly on
`::1` — the assertion passed on an accident, not on the behaviour it names.

Production is not implicated: the bound, the size check, the retry count and the temp cleanup all behaved
as designed. Fix is in the test only: each case now answers **every request it causes**, through a
dispatcher, with a plain 500 as the fallback so a request can never be left hanging. Same assertions,
unweakened — the 64-byte bound, the "incomplete" failures, and `directory.listFiles()` empty after the
first failure and again at the end.
