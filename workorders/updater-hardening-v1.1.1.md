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
