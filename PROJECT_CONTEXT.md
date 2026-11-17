# PROJECT_CONTEXT — condensed spec (full original spec kept by owner as APK_SPEC.md)

Goal: offline-first toolkit to inspect, analyze, modify, rebuild, sign and export APKs the user owns/is authorized to modify. Platform: Windows desktop (decided for speed). Engines must stay UI-independent, behind interfaces, replaceable.

## Features (A-M)
A Import/analysis: metadata (name, package, versionName/Code, min/target SDK), ABIs, multidex, SHA-256, ZIP contents, detect malformed APKs, original preserved.
B Manifest editor: decode binary AXML, edit, inspect components/intent filters, validate before rebuild.
C Activity manager: list, exported/enabled, enable/disable, add/remove declaration (declaration != DEX implementation).
D Permission manager: extract, categorize (dangerous/normal), add/remove, validate.
E DEX explorer: multidex, packages/classes/fields/methods, hierarchy, refs, baksmali/smali.
F Smali editor: syntax-aware, navigation, search/replace, validate, assemble.
G Package/class manager: search, references, unused detection (reflection-aware), impact preview, controlled removal.
H Hook/method-reference analyzer: user-configured signatures (class, package prefix, method, params, return); finds invoke-* (and /range) across all DEX; extract type/target/containing class+method/dex/location; review workflow, preview, remove selected invocations safely, revalidate. Never auto-label unknown methods malicious.
I Dependency analysis before any removal: DEX refs, inheritance, invocations, fields, manifest, resources, reflection strings, cross-DEX, native libs. Warn on possible breakage.
J Resource explorer/editor: strings, colors, dimens, layouts, drawables, mipmaps, replace images, validate refs, recompile at build.
K Build pipeline: validate -> decode -> isolated copy -> apply changes -> validate manifest/DEX/resources -> compile resources -> assemble DEX -> unsigned APK -> ZIP align -> sign -> verify -> validate -> export. Align BEFORE signing; any later change = re-align + re-sign.
L Signing: profiles, import keys, sign, verify, show cert info, encrypted key storage (no plaintext keys/passwords).
M Projects: one workspace per APK, immutable original, working copy, change record, build history/errors, restore states, interruption recovery. Metadata + file paths in DB only.

## Security/Performance
Untrusted input; zip-slip + zip-bomb protection; size/time limits; no uploads; clear errors; destructive changes need preview + validation. Background work, streaming, progress, cancellation, memory-aware.

## Stack (proposed, Windows)
Kotlin + Compose Multiplatform Desktop, JVM 17, Gradle Kotlin DSL, Coroutines/Flow, Koin, SQLite (SQLDelight), JSON settings, PKCS12 encrypted keystore. Candidate libs (verify license + behavior): Apktool, dexlib2, smali/baksmali, apksig.

## Roadmap
0.1 inspection foundation | 0.2 manifest/components | 0.3 DEX/Smali/analyzer | 0.4 resources | 0.5 build+sign | 1.0 integrate.
v0.1 scope: foundation, import, ZIP validation, SHA-256, metadata, binary manifest parse, activity+permission view, DEX/multidex detect, file explorer, projects, error handling, automated tests. NO modification/rebuild/signing.

## Tests (every engine)
Small/large/multidex/malformed APKs, binary manifests, odd resources, complex smali, missing refs, invalid signatures, interrupted builds, signature failures.
