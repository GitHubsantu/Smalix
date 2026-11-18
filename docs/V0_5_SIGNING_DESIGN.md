# v0.5 signing boundary

Status: proposed; implementation requires approval.

## First supported slice

- Input is only the fully reinspected `work/builds/<id>/aligned.apk` evidence.
- Use the already-tested `apksig:8.7.3` path with a user-owned PKCS12 keystore, APK Signature Scheme v2 only, and minimum SDK 24.
- Write only `work/builds/<id>/signed.apk`, through a unique build-local temporary output and a non-overwriting final move. Never touch `original.apk`, `aligned.apk`, or an export path.
- Immediately verify the output with `ApkVerifier`, then re-run ZIP, manifest, and DEX inspection. Any failure stops the build before export.

V1, v3/v3.1, source-stamp, lineage/rotation, JKS/BKS, and Windows certificate-store signing are explicitly rejected until separately tested.

## Profile and secrets

A persisted `SigningProfile` contains only: opaque ID, display name, PKCS12 file reference, alias, certificate SHA-256, creation/update times, and enabled status. It contains no APK bytes, key bytes, private key, password, recovery material, or exported certificate chain.

The PKCS12 itself remains encrypted by its own password at the user-selected reference. Keystore and key passwords are supplied per operation as `CharArray`, used only to load/sign, then cleared in `finally`; they must never enter SQLite, JSON, logs, exceptions, crash reports, analytics, or UI state. Profile metadata needs no additional encryption because it contains no secret. A future managed-key-copy feature would need an explicit Windows-DPAPI design and approval.

## Contract and checks

`Signer` receives the aligned `ValidatedBuildOutput`, a profile reference, in-memory passwords, and a deadline. It must reject a stale/missing/non-`aligned.apk` input, mismatched certificate fingerprint, unavailable alias, unsupported signing scheme, pre-existing `signed.apk`, expired deadline, or cancelled operation. It returns only a build-local signed path plus verified certificate summary; passwords never appear in the result.

Before signing, recompute/compare the aligned input's SHA-256 so post-alignment changes cannot be signed. After signing, `ApkVerifier` must report verified v2 signing and the expected signer certificate SHA-256. The final inspection must bind the resulting `signed.apk` SHA-256, manifest, and DEX inventory for a future explicit export stage.

## Tests and fixtures

- Portable temporary PKCS12: success, source SHA unchanged, distinct signed output, v2 verification, expected certificate fingerprint.
- Wrong store/key password, wrong alias, missing keystore, changed input, existing output, signer timeout/cancellation, and invalid/unsupported profile all return typed failures without export.
- Owner fixture later: authorized APK plus a disposable PKCS12 profile. Do not commit APKs, keystores, certificates, or passwords.

No signing code, profile persistence, UI, or export is included in this design step.
