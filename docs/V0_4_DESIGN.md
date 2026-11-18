# v0.4 design — staged resources

Status: proposed; owner approval required before code.

## Boundary

`original.apk` remains immutable. v0.4 may inspect `resources.arsc` and resource ZIP paths, and stage edited text values under `work/resources/`; it does not replace archive entries, compile resources, rebuild, export, align, or sign. Those output operations remain v0.5.

## First increment

- Add typed resource models/contracts for a bounded read-only index: resource ID, package/type/name, configuration qualifier, raw value/reference, and source entry path. **Implemented; parser pending.**
- Implement a small in-house binary resource-table reader for simple string/color/dimen values only. Unknown chunks/types/configurations return typed unsupported or malformed results; they are never silently changed.
- Desktop explorer lists types and searches exact name/value. Resource-file inventory remains read-only and uses the validated ZIP inventory.

## Staged edits

- Permit only literal string, color, and dimen value drafts after explicit selection; do not edit resource IDs, names, qualifiers, XML layouts, or binary drawables in the first slice.
- Store draft text at `work/resources/<draft-id>.json` atomically. SQLite stores only draft ID/project ID/original SHA/path/revision/timestamps/status—never APK bytes or resource-table payloads.
- Every open/save rechecks the original SHA-256 and matching workspace/metadata. Invalid syntax, unsupported reference edits, duplicate target drafts, stale SHA, or mismatched metadata are rejected without overwriting the old draft.
- Validation is lexical and type-specific only: strings are valid UTF-8 text; colors are `#RRGGBB` or `#AARRGGBB`; dimens are finite decimal plus `dp`, `sp`, `px`, `pt`, `in`, or `mm`. `@...` references stay read-only in this milestone.

## Limits and tests

- Read only validated `resources.arsc`; cap table bytes, packages/types/entries/string lengths/results, and parse deadline. Never extract arbitrary archive paths.
- Test generated valid/simple tables, malformed offsets/chunks, caps, raw references, qualifier collisions, stale draft rejection, atomic save/discard, and unchanged original SHA/bytes.
- Fixtures needed later: owned APKs with literal strings/colors/dimens, raw references, locale/night qualifiers, and a malformed resource-table sample. No fixture is copied into the repository.

## Library decision

No resource library is proposed for the first increment; use an in-house bounded reader. Apktool/aapt2 remain v0.5 candidates and are **unverified** for this project.
