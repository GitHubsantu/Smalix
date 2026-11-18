# v0.5 export boundary

Status: implemented and test-verified on 2026-11-18; no export UI or export-history persistence is included.

## Scope

Export is the final copy of a fully reinspected build-local `work/builds/<id>/signed.apk` to an owner-selected `.apk` destination. It is not signing, rebuilding, installation, sharing, upload, or an overwrite feature.

The first implementation has no Compose control. A future UI may obtain a destination from the Windows native save dialog, then pass only that path to the UI-independent use case.

## Preconditions and contract

`ApkExportUseCase` receives the `BuildWorkspace`, final `ValidatedBuildOutput` from signed-output ZIP/manifest/DEX reinspection, an explicit destination `Path`, and a deadline.

It accepts only evidence whose normalized source is exactly `BuildWorkspace.root/signed.apk`. Before copying, it recomputes source size and SHA-256 and requires both to match that evidence. It rejects an expired/cancelled request, missing source, non-`.apk` destination, destination inside the project/workspace tree, missing/non-directory parent, existing destination, source destination, or a normalized destination escaping its selected parent.

The result contains only destination path, final SHA-256, byte size, and completion time. It contains no APK bytes, private keys, certificates, aliases, or passwords. The first slice does not persist an export record; build-history metadata needs separate design approval.

## Write and verification sequence

1. Create a unique temporary file next to the selected destination with create-new semantics.
2. Stream-copy `signed.apk` to it under the deadline; do not load the APK into memory.
3. Hash/count the temporary output and require exact equality with signed evidence.
4. Move temporary to the final destination by non-replacing atomic move. If unavailable, reject and remove only the owned temporary file.
5. Re-read final size/SHA-256. Return success only when it still equals signed evidence.

No step modifies `original.apk`, `input.apk`, `manifest-applied.apk`, `aligned.apk`, `signed.apk`, drafts, or an existing user file. Failures/cancellation remove only the temporary export; success is never silently removed or overwritten.

## Safety and errors

- Stream copy is bounded by validated signed size and deadline; source stays unchanged.
- Normalized absolute paths are validated before writing; workspace containment is rejected.
- Errors never include secrets or byte content. Future logs may contain path, SHA-256, size, stage, and I/O category only.
- Access-denied, file lock, or non-atomic filesystem behavior is a typed rejection/failure, never an overwrite retry.

## Tests and fixture

- Portable temporary-directory success: source stays unchanged and destination SHA/size match.
- Existing destination, changed source, wrong evidence path, workspace destination, missing parent, expired deadline, and interrupted copy fail without replacing a user file.
- A failed temporary export is removed and creates no final path.
- Owner manual check later: choose a new external filename, independently compare SHA-256, and confirm both original and build-local signed APK are unchanged.

## Library check

No new library is proposed. Implementation uses JDK 17 `java.nio.file` plus streaming `MessageDigest`; no new license or compatibility claim is made.

## Implementation note

`ApkExportUseCase` is the UI-independent implementation. It accepts only the fixed `signed.apk` evidence, rehashes before and after a streamed sibling-temporary copy, requires a non-replacing atomic final move, and returns only destination metadata. It rejects workspace destinations and keeps no export record.
