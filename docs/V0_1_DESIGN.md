# v0.1 design — inspection foundation

Status: approved design; implementation and test status are recorded in `PROGRESS.md`.

## 1. Modules and dependency graph

Keep the existing 12 modules. The dependency direction below is the rule; `app` is the only composition root and wires concrete engines, storage, and database services.

```text
core-model     core-common
     ^             ^
     |             |
 engine-api -------+
     ^
     |-------------------------+
engine-apk  engine-manifest  engine-dex

feature-home / feature-projects / feature-inspector --> core-model, engine-api
app --> all modules (composition and desktop entry point)
core-database --> core-model, core-common, engine-api
core-storage  --> core-model, core-common, engine-api
```

`core-model`: immutable domain/result models. `core-common`: errors, clocks, hashing and cancellation helpers. `engine-api`: inspection plus project/workspace contracts (`ApkAnalyzer`, `ManifestParser`, `DexDetector`, `ProjectRepository`, `WorkspaceStore`). `engine-*`: UI-free implementations. `core-database`: SQLite `ProjectRepository` implementation. `core-storage`: `WorkspaceStore` implementation. Features must not depend on `engine-*`, `core-database`, or `core-storage`; they receive contracts from `app`.

## 2. Engine contracts and models

Put the interfaces in `engine-api`, backed by immutable request/result models in `core-model`:

- `ApkAnalyzer.analyze(request)`: validate an APK ZIP and return SHA-256, ZIP entry inventory, APK size, ABI list, and structured warnings/errors. Input is a `Path`; it never returns or persists APK bytes.
- `ManifestParser.parse(request)`: parse only `AndroidManifest.xml` from a validated archive and return package/version/SDK data, application label, activities and permissions. Unsupported or malformed binary XML returns a typed failure, never a partial silent result.
- `DexDetector.detect(request)`: inspect validated ZIP entry names and return ordered `classes*.dex` entries, count, and `isMultidex`. It does not load or execute DEX.
- `ProjectRepository`: create, find, list, update, and delete project metadata records. It persists only the `Project` fields below; no APK content is part of its contract.
- `WorkspaceStore`: create a project workspace, atomically copy one original APK into its fixed `original.apk` location, expose its layout, and deliberately remove a workspace. Its implementation must never overwrite or mutate an imported original.

Shared models: `AnalysisRequest(projectId, apkPath, deadline, limits)`, `AnalysisResult`, `ZipEntryInfo`, `ManifestInfo`, `ManifestLabel`, `ActivityInfo`, `PermissionInfo`, `DexInfo`, `Project`, `Workspace`, `AnalysisWarning`, and a sealed `AnalysisFailure` (including `UnsafeArchive`, `LimitExceeded`, `MalformedApk`, `UnsupportedFormat`, `IoFailure`, `Cancelled`). Paths are `java.nio.file.Path` at the engine boundary; persisted paths are strings relative to the workspace root.

App labels are deliberately **not resolved from `resources.arsc` in v0.1**. `ManifestLabel` preserves either a literal manifest value or the raw resource reference (for example, `@string/app_name`); the UI displays that raw reference. Resolving it is deferred to the resource milestone, so the manifest slice has no hidden resource-parser dependency.

Format gate: `.aab` and `.xapk` inputs are rejected by `ApkAnalyzer` before ZIP analysis; split APKs are rejected when `ManifestParser` sees the manifest `split` attribute (with conventional split filename detection as an early hint). Each is an `UnsupportedFormat` with a message stating that v0.1 accepts one standalone APK only.

## 3. Project database

SQLite stores metadata and paths only—never APK bytes, keystores, or passwords. Keep v0.1 to these entities:

| Entity | Essential fields |
|---|---|
| `Project` | `id` (UUID), `displayName`, `createdAt`, `lastOpenedAt`, `workspaceRelativePath`, `originalRelativePath`, `originalSha256`, `sourceFileName`, `originalSizeBytes`, `analysisStatus`, `analysisError` |
| `ApkAnalysis` | `projectId`, `analyzedAt`, package/version/SDK metadata, ABI list (normalized child rows or JSON), DEX count, ZIP entry count, warnings JSON |
| `WorkspaceLog` | `id`, `projectId`, `createdAt`, `level`, `event`, `message`, `relativeLogPath` |

`Project.id` is the owner key. Use foreign keys and delete rows only when the matching workspace is deliberately removed. Build history is out of scope until v0.5.

## 4. Workspace

```text
files/projects/<project-id>/
  original.apk       # imported once; never modified, opened read-only
  work/              # v0.1 temporary validated extraction/analysis output; disposable
  logs/              # per-operation logs, no APK bytes or secrets
```

Import copies into `original.apk` through a temporary sibling, verifies its SHA-256, then atomically renames it. All paths are resolved under the project root; `work/` is cleared only through an explicit, bounded workspace operation.

## 5. ZIP safety policy

Apply limits while streaming, before manifest/DEX inspection: reject absolute, drive-qualified, empty, `.` or escaping `..` entry paths; normalize with `/`; reject duplicate normalized paths and symlink-like extraction targets. Do not extract unless needed; any extraction must resolve under `work/`.

Proposed initial limits (centralized and configurable only at build time): source archive ≤ 1 GiB; ≤ 100,000 entries; each inflated entry ≤ 256 MiB; total inflated bytes ≤ 2 GiB; compression ratio ≤ 100:1 **only for entries whose uncompressed size exceeds 1 MiB**; entry name ≤ 4,096 characters; and a 120-second cancellable import deadline. Total-size limits always apply. Check claimed metadata early and count actual streamed bytes so forged ZIP sizes cannot bypass limits. Timeouts, interruption, malformed ZIPs, and limit breaches fail the import with a typed error and leave no accepted project.

## 6. Tests and fixtures needed

Unit-test ZIP validation, hashing, manifest parsing, DEX detection, workspace containment, and SQLite mapping. Add integration tests that import fixtures into a temporary workspace, assert metadata, and assert the original SHA-256 remains unchanged. Include cancellation/deadline and every safety boundary above.

Please provide APKs you own or may distribute, plus expected metadata (package, version, SDKs, permissions, activities, ABIs, and expected DEX count):

- a small valid APK with one `classes.dex`;
- a valid multidex APK (`classes.dex`, `classes2.dex`, ...);
- an APK with binary manifest edge cases (namespaces, aliases, intent filters);
- valid APKs with native libraries for at least one ABI, and without native libraries;
- deliberately malformed ZIP/APK samples: traversal name, duplicate normalized name, corrupt central directory, extreme compression ratio, and manifest missing/malformed.

Keep malicious/oversize fixtures outside Git; generate them in tests or obtain them from a controlled local fixture location. Fixtures must have known SHA-256 values.

## 7. Library check

No new dependency is approved by this document or added to Gradle. Proposed candidates are deliberately **not verified**: no compatibility test was run in this design step.

| Need | Candidate | License stated by upstream | Desktop/JVM 17 compatibility | Status |
|---|---|---|---|---|
| SQLite driver | `org.xerial:sqlite-jdbc:3.47.2.0` | Apache-2.0 embedded in artifact; also retain embedded `LICENSE.zentus` notice when packaging | JDK 17 CRUD smoke test passed | Verified 2026-11-17 |
| SQL mapping | SQLDelight | Apache-2.0 (must verify selected version/artifact) | untested | Not verified |
| Binary AXML | Small in-house parser | project-owned | design only | Not verified; preferred to avoid a new parser dependency |
| DEX parsing (future depth) | dexlib2 | BSD-3-Clause (must verify selected artifact) | untested | Not verified; v0.1 detector needs no DEX library |

Before adoption, record exact version, artifact license/NOTICE, transitive licenses, and a JDK 17 desktop integration test result here and in `PROGRESS.md`.
