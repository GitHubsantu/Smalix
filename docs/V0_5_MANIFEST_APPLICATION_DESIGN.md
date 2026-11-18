# v0.5 manifest application design

Status: proposed after Apktool 3.0.3 failed the isolated SimBridge round-trip.

## Decision

Use an in-house, lossless binary-AXML document layer for manifest drafts. Do not use Apktool for draft application. Resource and Smali drafts remain unsupported by this slice.

## Boundaries

- Read `AndroidManifest.xml` only from the SHA-bound isolated build input.
- Decode every XML chunk, namespace, typed attribute, raw string index, and unknown element/attribute into a document tree; preserve information the UI does not expose.
- A no-op round trip returns the original manifest bytes. An edit changes only its target field and preserves unrelated document content and typed values.
- Reject a draft if its target is ambiguous, missing, raw-resource-backed where a literal is required, or cannot be represented without data loss.
- The eventual ZIP-entry replacement is only in `work/builds/<id>/`; `original.apk`, staged drafts, SQLite metadata, export, alignment, and signing are out of this slice.

## First implementation slices

1. Add a bounded, UI-independent `BinaryXmlDocumentReader` that validates and retains the full binary XML structure. Test no-op byte preservation and malformed bounds.
2. Add a `ManifestDraftApplier` that returns an in-memory replacement manifest only. Start with literal label/version/SDK/permission changes; unsupported activity structural changes return a typed rejection.
3. Independently test isolated ZIP entry replacement, then re-run ZIP, manifest, and DEX inspection. Carry only that fixed build-local output's analyzer SHA-256/size plus its manifest and DEX evidence forward in memory. ZIP alignment consumes this evidence, writes only `aligned.apk`, and reinspects that output; any later byte-changing stage must do the same.

## Fixtures

Need owned APKs with namespace declarations, intent filters/meta-data/providers, literal and `@string/...` labels, typed numeric/boolean attributes, and expected manifest SHA-256. Do not commit APKs or generated outputs.
