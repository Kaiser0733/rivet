# Mozilla CA bundle

`toolchain/src/main/assets/ca-certificates.pem` is the curl.se Mozilla CA
extract from <https://curl.se/ca/cacert.pem>, downloaded 2026-11-09.

- SHA-256: `427c064216761a1e72a8b30f0d954d08cb6e4435a82091fb865afd20c8497285`
- License: MPL-2.0 (the bundle's certificate data; individual roots retain
  their own notices in the PEM).
- Refresh: replace the asset, bump `CA_BUNDLE_VERSION` in
  `app/src/main/java/com/kaiser/rivet/runtime/Toolchain.kt`, and update the
  hash/date here.

The app extracts this file to `filesDir/toolchain/etc/ssl/cert.pem` and sets
`SSL_CERT_FILE` to it, so Python and curl use the same trust store on every
device instead of depending on a system CA path.
