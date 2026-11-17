# Smalix — Progress File

Status: **PLANNING** (no code written) · Platform: **Windows desktop (decided)** · Current milestone: **v0.1 Inspection Foundation**
Rule: plan → discuss → approve → implement small slices → test → update this file.

## Milestones
| Ver | Scope | Status |
|---|---|---|
| 0.1 | Import, ZIP validation, SHA-256, metadata, binary manifest parse, activities/permissions view, DEX/multidex detect, file explorer, projects, tests | Planning |
| 0.2 | Manifest/activity/permission editing + validation | Not started |
| 0.3 | DEX browser, Smali disasm/edit/asm, hook-reference analyzer | Not started |
| 0.4 | Resource explorer/editor, validation | Not started |
| 0.5 | Rebuild, align, sign, verify, export | Not started |
| 1.0 | Integration + stabilization | Not started |

## Platform decision
Windows desktop app (speed). Android-only parts of the spec are replaced:
| Spec (Android) | Windows replacement (proposed) |
|---|---|
| Compose Android | Compose Multiplatform Desktop (Kotlin/JVM 17) |
| Hilt | Koin (simple DI) |
| Room | SQLite via SQLDelight (metadata + paths only) |
| DataStore | JSON settings file |
| SAF | native file chooser |
| Android Keystore | password-encrypted PKCS12 keystore (+ optional Windows DPAPI) |
| aapt2 risk | gone: Apktool + bundled aapt2.exe run natively |
Kept: engine interfaces, security rules, roadmap, never-store-APK-in-DB, original APK immutable.

## Proposed architecture (to confirm; v0.1 module list below still applies, drop Android-specific bits)
- Kotlin, Compose, MVVM + Clean Arch, Hilt, Room (metadata + file paths only, never APK bytes), DataStore, SAF.
- **v0.1 modules only (fewer than the spec's 30; split later when needed):**
  `app`, `core-model`, `core-common`, `core-database`, `core-storage`, `engine-api` (interfaces), `engine-apk` (ZIP/hash/metadata), `engine-manifest` (binary XML), `engine-dex` (detection only), `feature-home`, `feature-projects`, `feature-inspector`.
- Engines are pure Kotlin/JVM modules, UI-independent, behind interfaces → unit-testable without a device.
- Project workspace: `files/projects/<id>/{original.apk (read-only), work/, logs/}`.

## Library compatibility — TO VERIFY before use (nothing tested yet)
| Need | Candidate | Concern |
|---|---|---|
| Binary manifest | own parser or an AXML lib | Prefer small own parser for v0.1; check license of any lib |
| DEX read | dexlib2 | Check Android-runtime build/fork, Guava weight, license |
| Smali/baksmali | smali (v0.3) | Same as above |
| Signing | apksig | Reported to work on Android; must test |
| Resources compile | Apktool / aapt2 | Low risk on Windows now; verify Apktool version + aapt2.exe bundling/licenses |

## v0.1 pre-code checklist
- [x] Owner approves design decisions below
- [x] Module dependency graph (design documented)
- [x] Engine interfaces (`ApkAnalyzer`, `ManifestParser`, `DexDetector`) (design documented)
- [x] SQLite entities (metadata/paths only) (design documented)
- [x] Storage/workspace layout + zip-safety limits (design documented)
- [x] Test strategy + required sample APK fixtures (design documented)
- [ ] Verify licenses of chosen libs

## Decisions
1. Keep the reduced 12-module v0.1 layout.
2. Use a small in-house binary AXML parser; do not resolve `@string/...` labels from `resources.arsc` in v0.1. Preserve/display literal labels or raw references.
3. JDK 17 setup is verified by owner.
4. Test APK fixtures remain needed: owned small, multidex, manifest-edge-case, ABI/no-ABI, and controlled malformed samples with expected metadata/SHA-256.
5. App/package name: Smalix / `com.imdevops.smalix`.

## Handoff (for new chat / other account / Codex / Claude Code)
Files: AGENTS.md (rules), PROJECT_CONTEXT.md (spec), PROGRESS.md (this). Save original spec as APK_SPEC.md in the same folder.
Prompt for a new session: "Read AGENTS.md, PROGRESS.md, PROJECT_CONTEXT.md. Continue from Next step."

## Structure setup (done; baseline verified by owner)
Created: Gradle multi-module skeleton (12 modules), version catalog (versions unverified), tiny Compose window in :app, docs/, test-fixtures/, .gitignore, README.
Assumptions (change if wrong): package `com.imdevops.smalix`, JDK 17, own manifest parser, Koin DI.
Owner verified the Gradle build with JDK 17, Kotlin 2.1.10, Compose 1.7.3, and `:app` run. This agent did not rerun it.

## Next step
Await owner direction. Do not start engine implementation until the owner selects the next slice and provides/approves the needed APK fixtures.

## Log
- Spec received and reviewed. Progress file created.
- Renamed project to Smalix (package com.imdevops.smalix), chosen by owner. Earlier names ApkEditor/Dexora were not unique. Uniqueness of Smalix not yet checked (GitHub/domain).
- Decision: target Windows desktop for speed (mobile later by reusing engines).
- 2026-11-17: Design-only v0.1 plan added in `docs/V0_1_DESIGN.md`; no feature code added. Confirmed the existing 12 modules and documented `app` as the only composition root. Proposed bounded ZIP policy, metadata-only SQLite entities, workspace layout, engine contracts, and fixture/test plan.
- 2026-11-17: Library candidates are explicitly **not verified**. No dependency was added and no license or compatibility test was run in this design step.
- 2026-11-17: Owner reports the baseline Gradle build works with JDK 17, Kotlin 2.1.10, Compose 1.7.3, and `:app` runs. This session did not rerun it.
- Test log (this session): no tests run (documentation-only task).
- 2026-11-17: Owner approved the design with four changes: add `ProjectRepository`/`WorkspaceStore`; preserve raw manifest label references rather than parsing `resources.arsc`; apply the 100:1 ZIP ratio only above 1 MiB while always enforcing total limits; reject AAB, XAPK, and split APKs as `UnsupportedFormat`.
- 2026-11-17: Implemented only the contract slice. Added `InspectionModels.kt` and `ProjectModels.kt` in `core-model`; added inspection and project/workspace interfaces in `engine-api`; added unit tests for model invariants and unsupported-format results. No engine, database, storage, or UI implementation was added.
- Test log (2026-11-17): `./gradlew test --no-daemon --console=plain` with JDK 17 and a project-local Gradle cache: **BUILD SUCCESSFUL in 41s** (`23 actionable tasks: 1 from cache, 22 up-to-date`; `:core-model:test FROM-CACHE`, `:engine-api:test UP-TO-DATE`). Gradle reported deprecated features warning; no test failures.
- 2026-11-17: Expanded `.gitignore` for local Gradle/Kotlin output, runtime workspace/database data, signing material, crash dumps, Windows metadata, and local APK fixtures. No tests run (ignore-rule-only change).
