# v0.3 design — read-only DEX exploration

Status: proposed; owner approval required before code.

## Boundary

v0.3 reads only the DEX entries already accepted by the ZIP analyzer. `original.apk` stays immutable. No DEX/Smali edits, assembly, APK rebuild, export, or signing are included in the first v0.3 increment.

## First increment

- Add typed DEX models in `core-model`: dex entry, class, method, field, access flags, and source location.
- Add `DexReader` and `DexSearch` contracts in `engine-api`; `engine-dex` implements them without UI dependencies.
- Browse multidex classes by package, show class/member summaries, and search exact/prefix class names.
- Read each `classes*.dex` directly from the validated APK with per-entry byte caps and a deadline. Never execute DEX.

## Disassembly and hooks (later v0.3 slices)

- Disassembly is read-only text initially: class/method selection produces bounded Smali text with offsets and DEX name. No save button or assembler.
- Hook-reference analysis accepts explicit user signatures and reports `invoke-*`/`invoke-*/range` matches with caller, target, DEX, and offset. It never labels unknown code malicious and never removes invocations automatically.
- Any edit/assembly proposal needs a separate approved design and remains staged until v0.5 rebuild work.

## Safety and storage

- Reuse validated inventory; reject absent/noncanonical DEX names and enforce maximum DEX/class/member/string counts plus cancellation/deadline checks.
- Keep parsed indexes in memory for the session. SQLite may store only optional query/history metadata, never DEX bytes or disassembly bodies.
- Show typed malformed/limit/I/O errors; do not silently skip corrupt classes.

## Library decision

Candidate: `org.smali:dexlib2` (and `baksmali` only for read-only text). License/JDK 17 compatibility are **unverified**; do not add either dependency until an artifact-license inspection and a JVM smoke test pass. If unsuitable, implement a bounded in-house DEX reader for the first increment.

## Tests and fixtures

Use a small single-DEX APK, owned multidex APK, compact DEX with invalid offsets/string IDs, large class-count limit fixture, and hook cases for normal and `/range` invokes. Assert DEX bytes and `original.apk` SHA-256 remain unchanged.

## Proposed slices

1. Models/contracts and fixture-independent parser-limit tests.
2. Bounded class/package index and search with tests.
3. Desktop read-only browser and manual verification.
4. Read-only Smali and hook-query design/implementation after separate approval.

## Hook-query increment (proposed)

Input is an explicit signature: target class descriptor, method name, parameter descriptor list, and return descriptor; package-prefix queries are allowed only when clearly marked as a broad search. The reader scans bounded method instruction streams for `invoke-*` and `invoke-*/range`, returning only caller class/method, target signature, DEX entry, and code offset. Results are informational: no automatic risk label, deletion, patch, or export.

The current descriptor-only reader cannot support this safely yet. First add bounded method/proto/code-item parsing with malformed-offset tests, then add the query engine and generated normal/range-invoke fixtures. Each scan checks byte/instruction/result limits and cancellation/deadline. Approval is required before this code.

## Smali staged-edit increment (proposed)

Smali editing is staged text only under `work/dex/`; `original.apk` and source DEX remain immutable. The first slice creates an explicit per-method draft, validates syntax/assembly in an isolated temporary directory, and never writes an APK, replaces DEX in an APK, exports, or signs.

Each draft binds original APK SHA-256, DEX entry, class/method descriptor, revision, timestamps, and validation status. SQLite stores metadata/path only; staged Smali text stays in the workspace. SHA mismatch, missing method, malformed Smali, duplicate draft target, limits, or assembly failure produce errors. `smali` needs separate license/JDK verification. Test stale SHA, invalid Smali, cleanup, limits, and unchanged original SHA-256. Rebuild stays deferred to v0.5.
