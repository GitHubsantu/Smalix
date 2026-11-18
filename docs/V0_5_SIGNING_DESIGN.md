# v0.5 signing boundary

Status: proposed; implementation requires approval.

## Requested signing options

- Input is only the fully reinspected `work/builds/<id>/aligned.apk` evidence.
- Use `apksig:8.7.3` with explicit v1, v2, and v3 selection. At least one scheme is required. V2 requires minimum SDK 24; v3 requires minimum SDK 28. V3.1, source stamps, and key rotation remain unsupported.
- Support a user-owned PKCS12 keystore or an explicit PKCS#8 `.pk8` private-key file paired with an X.509 PEM certificate. The supplied `testkey.pk8` / `testkey.x509.pem` pair is test-only and is never copied, bundled, or used as a shipped default profile.
- Write only `work/builds/<id>/signed.apk`, through a unique build-local temporary output and a non-overwriting final move. Never touch `original.apk`, `aligned.apk`, or an export path.
- Immediately verify the output with `ApkVerifier`, then re-run ZIP, manifest, and DEX inspection. Any failure stops the build before export.

V3.1, source stamps, lineage/rotation, JKS/BKS, and Windows certificate-store signing are explicitly rejected until separately tested.

## Profile and secrets

A persisted `SigningProfile` contains only: opaque ID, display name, key-reference type (`PKCS12` or `PKCS8_X509`), keystore/private-key and certificate file references, alias where PKCS12 requires one, certificate SHA-256, selected v1/v2/v3 schemes, creation/update times, and enabled status. It contains no APK bytes, key bytes, private key, password, recovery material, or exported certificate chain.

The PKCS12 itself remains encrypted by its own password at the user-selected reference. Keystore and key passwords are supplied per operation as `CharArray`, used only to load/sign, then cleared in `finally`; they must never enter SQLite, JSON, logs, exceptions, crash reports, analytics, or UI state. Profile metadata needs no additional encryption because it contains no secret. A future managed-key-copy feature would need an explicit Windows-DPAPI design and approval.

## Contract and checks

`Signer` receives the aligned `ValidatedBuildOutput`, a profile reference, selected v1/v2/v3 schemes, in-memory passwords, and a deadline. It must reject a stale/missing/non-`aligned.apk` input, mismatched certificate fingerprint, unavailable alias, missing PKCS#8/X.509 pair, unsupported SDK/scheme combination, pre-existing `signed.apk`, expired deadline, or cancelled operation. It returns only a build-local signed path plus verified certificate summary; passwords never appear in the result.

Before signing, recompute/compare the aligned input's SHA-256 so post-alignment changes cannot be signed. After signing, `ApkVerifier` must report every selected v1/v2/v3 scheme as verified and the expected signer certificate SHA-256. The final inspection must bind the resulting `signed.apk` SHA-256, manifest, and DEX inventory for a future explicit export stage.

## Tests and fixtures

- Portable temporary PKCS12 and local explicit PKCS#8/X.509 test profile: v1-only, v2-only, v3-only (SDK 28+), and v1+v2+v3 success; source SHA unchanged; distinct signed output; expected certificate fingerprint.
- Wrong store/key password, wrong alias, missing key/certificate, invalid PEM/PKCS#8, changed input, existing output, signer timeout/cancellation, and invalid/unsupported SDK/scheme combinations all return typed failures without export.
- Owner fixture later: authorized APK plus a disposable profile. Do not commit APKs, private keys, keystores, certificates, or passwords.

No signing code, profile persistence, UI, or export is included in this design step.
