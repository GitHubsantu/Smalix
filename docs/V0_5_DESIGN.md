# v0.5 design — isolated rebuild, signing, export

Status: owner approved; toolchain verification in progress.

## Boundary

`original.apk` is never modified. Every build starts from a fresh copy in a unique, store-owned `work/builds/<build-id>/` directory. Only an explicitly selected export path receives a final APK; failed/cancelled build directories remain quarantined in `logs/` until the owner removes them.

## Pipeline

1. Re-inspect the immutable original; require its SHA-256 to match the project and every selected staged draft.
2. Validate staged manifest, Smali, and literal-resource drafts. Reject stale, invalid, missing, duplicate, or unsupported drafts before output starts.
3. Copy `original.apk` into the isolated build directory and apply changes only there. Build logs record inputs, tool versions, SHA-256 values, warnings, and failure stageâ€”never key passwords.
4. Validate modified manifest/DEX/resources; create an unsigned APK; `zipalign` it; sign the aligned APK; verify the resulting signatures; then re-run ZIP/manifest/DEX checks.
5. Atomically copy the verified final APK to the owner-selected export file. Never overwrite an existing export without explicit confirmation.

Any change after alignment requires a new alignment and signature. No staged draft is applied back to `original.apk`.

## Required first implementation decisions

- Introduce a UI-independent build contract/result model and cancellation/progress reporting. A separate `engine-build` module is proposed only after approval; its dependencies would be `core-model`, `core-common`, and `engine-api`.
- Before draft application, prove a reversible isolated-copy pipeline using an owned fixture and no signing key. No writer or output export in that verification slice.
- Manifest/resource/DEX application must be separately validated in temporary output. Current v0.2â€“v0.4 draft formats are staged data, not APK writers.
- Signing profiles contain alias/certificate metadata and an encrypted key reference only. Passwords are entered per operation and kept in memory only; no plaintext password/key is stored in SQLite, JSON, logs, or crash reports.

## Candidate tools — unverified

| Need | Candidate | License/JDK/Windows status |
|---|---|---|
| APK signatures | `com.android.tools.build:apksig:8.7.3` | Apache-2.0 POM and packaged `LICENSE` inspected. JDK 17/Windows tests verify an owner APK, reject an unsigned temporary APK without changing it, and sign/verify a temporary v2-only (min SDK 24) APK using a runtime-generated test key. Production profiles, user keys, and v1/v3 coverage remain unverified. |
| ZIP alignment | Android SDK Build-Tools 35.0.0 `zipalign.exe` | Local Windows behavior is verified: an intentionally unaligned stored ZIP failed check, a distinct aligned output passed check, and input SHA-256 stayed unchanged. `package.xml` identifies Build-Tools 35 but only references a generic SDK license ID; full license terms and output determinism remain unverified. |
| Manifest/resources | Apktool + bundled `aapt2.exe`, or in-house writers | Unverified; choose only after license/provenance/JDK/Windows tests. |
| DEX rebuild | verified `smali` temporary assembly plus a bounded DEX replacement pipeline | `smali` was verified only for temporary JDK 17 validation, not production APK rebuilding. |

No candidate is approved, added, or marked verified by this design.

## Safety, recovery, and tests

- Enforce workspace ownership, path containment, ZIP limits, process timeouts, cancellation, tool-output caps, and cleanup of only store-owned temporary paths.
- Test each stage with an owned signed APK: unchanged original hash/bytes, stale draft rejection, invalid draft rejection, cancellation, tool failure, interrupted build recovery, alignment-before-signing, signature verification failure, explicit-export no-overwrite, and exported APK inspection.
- Fixtures still needed: owned APK with a signing profile suitable for test distribution, v1/v2/v3 signature variants, staged manifest/Smali/resource changes, and controlled invalid signing material. Do not commit APKs, keys, passwords, or certificates unless explicitly approved.
