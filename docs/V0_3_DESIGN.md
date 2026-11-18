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

`org.smali:dexlib2:2.5.2` is verified for the current read-only hook scanner (BSD 3-Clause; JDK 17 smoke tested). `org.smali:smali:2.5.2` is verified only on the **test classpath**: its resolved POM declares BSD 3-Clause and its JDK 17 class-load smoke test passed. Its transitive set includes ANTLR 3.5.2, dexlib2, smali util, ANTLR runtime/stringtemplate, JCommander, and Guava Android; retain their notices before any distribution. No production assembler use or DEX/APK write path is approved yet. `baksmali` remains unverified.

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

## Smali staged-edit increment

Smali editing is staged text only under `work/dex/`; `original.apk` and source DEX remain immutable. The implemented storage slice atomically saves each per-method text draft as `work/dex/<draft-id>.smali`, verifies project/SHA binding on load, and never writes an APK, replaces DEX in an APK, exports, or signs.

Each draft binds original APK SHA-256, DEX entry, class/method descriptor, revision, timestamps, and validation status. SQLite stores metadata/path only; staged Smali text stays in the workspace. The next slice must add a use case that rejects SHA/method/metadata mismatches before opening or saving a draft. Syntax/assembly validation, duplicate-target handling, and limits remain pending; `smali` needs separate license/JDK verification. Tests cover stale/malformed/cross-project storage input and unchanged original bytes. Rebuild stays deferred to v0.5.

### Desktop staged-draft workflow (proposed)

1. In the existing read-only DEX browser, the user selects a class method already present in the bounded index. **Implemented; manual desktop verification pending.**
2. “Open staged draft” runs the SHA-safe use case. It opens an existing matching draft or creates a text-only metadata rendering; stale/inconsistent state is shown as an error and is not overwritten. **Implemented; manual desktop verification pending.**
3. A clearly labelled staged-text pane permits only draft-text changes. “Save staged text” creates a new revision after local size/binding checks; it states that `original.apk` is unchanged. **Implemented; manual desktop verification pending.**
4. “Discard staged draft” requires confirmation and removes only the matching `work/dex/<draft-id>.smali` text and its SQLite metadata. **Implemented; manual desktop verification pending.**

This slice has no assemble, validate-with-smali, DEX replacement, APK rebuild, export, or signing control.

### Staged text editing increment (proposed)

- The currently selected staged method draft gets a multiline editor showing only `work/dex/<draft-id>.smali` text. It is always labelled **staged text; original.apk unchanged**.
- “Save staged text” creates revision `n + 1` only when the loaded draft, project, workspace, metadata, and original SHA-256 still match. It replaces the workspace text atomically, then updates SQLite metadata; a failed metadata update leaves a visible inconsistency error rather than silently continuing.
- Local validation remains bounded and non-assembler: nonblank text, no NUL, at most 256,000 characters, `.method` opening and `.end method` closing. Full syntax/assembly validation is deferred until a separately verified `smali` dependency is approved.
- Discard remains confirmation-gated. No edit writes DEX/APK bytes, invokes a process, exports, rebuilds, aligns, or signs.
- Tests: save/reopen revision, stale SHA and metadata mismatch rejection, validation rejection with old text preserved, atomic-write failure handling, and unchanged original bytes/SHA.
