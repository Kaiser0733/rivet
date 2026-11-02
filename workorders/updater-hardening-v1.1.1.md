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

## STOP POINT — the tests are not written

The five fixes are committed and the code is balanced and import-clean by the structural checks, but
**no test pins them and no CI has run on this branch.** I ran out of context before writing them, and
writing them blind would have risked a broken commit and a wasted CI round, so this is where the run
stops. What must exist next, in `app/src/test/java/com/kaiser/rivet/updates/`:

1. **retry after a dropped connection** — MockWebServer enqueues `SocketPolicy.DISCONNECT_AT_START`
   then a good response; `check()` returns the release and the server saw 2 requests. Same for
   `download()`, asserting the file is complete.
2. **resume** — first response disconnects mid-body after N bytes; the second must arrive with
   `Range: bytes=N-` and answer `206` with `Content-Range: bytes N-.../size`; the download completes
   and the staged file equals the fixture bytes. A third case: server answers `200` to the Range
   request and the file still completes (clean restart, no stitching).
3. **fallback** — GitHub host unreachable for all attempts, mirror (MockWebServer) serves the same
   bytes: the download succeeds from the mirror, and a mirror file with a wrong signature or size is
   still rejected by `UpdateApks.verify`.
4. **copy pins** — the two transport sentences and the bad-status sentence, plus an assertion that
   "check your connection" cannot reappear in this family.
5. **version** — a pin that `versionCode == 25` and `versionName == "1.1.1"` so the bump cannot be lost
   in a merge.

Until those exist, treat this branch as **unverified**: the fixes are reasoned from the code, not
proven by a run. Hermes' CI on the current head will compile and run the existing suite (which must
stay green), but it cannot prove any of the five items above.
