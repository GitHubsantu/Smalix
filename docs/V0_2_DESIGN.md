# v0.2 design — staged manifest editing

Status: proposed. Approval is required before implementation.

## Scope and boundary

v0.2 edits a validated manifest **draft**, not `original.apk`. The original remains immutable and no APK is rebuilt, exported, aligned, or signed in this milestone.

Supported draft changes: literal/raw-reference app label, `versionName`, positive `versionCode`, min/target SDK, activity declarations (`name`, `exported`, `enabled`), and uses-permissions. Package-name changes, intent filters, activity aliases, services, receivers, providers, resources, and DEX changes are deferred. Adding an activity only adds a manifest declaration; it never claims a DEX implementation exists.

## Data and contracts

Add immutable `ManifestDraft`, `ManifestEdit`, `ManifestValidation`, and `ManifestIssue` models to `core-model`. `ManifestEdit` is a typed operation (set scalar, add/update/remove activity, add/remove permission), with a stable draft ID and the original APK SHA-256 it was created from.

Add contracts in `engine-api`:

- `ManifestDraftEditor`: applies one typed edit to a draft and returns a new draft plus validation.
- `ManifestDraftValidator`: validates a complete draft without touching ZIP/APK bytes.
- `ManifestDraftStore`: loads, atomically saves, and discards a draft for a store-owned workspace.

`engine-manifest` owns editor/validator implementation. `core-storage` owns the draft store. The `app` composition root wires them; feature modules receive only contracts.

## Persistence and workspace

Draft content is stored atomically under:

```text
files/projects/<id>/
  original.apk              # immutable
  work/manifest-draft.json  # staged, replaceable draft; never APK bytes
  logs/
```

SQLite stores only draft state metadata: project ID, original APK SHA-256, relative draft path, revision, timestamps, and validation status. It stores no APK or manifest byte/BLOB content. A SHA mismatch or missing draft prevents editing and requires re-inspection/recreation; it is never silently applied to a different APK.

## Validation and UX

The editor opens only after a successful inspection and starts from the parsed manifest. Every edit returns field-level errors/warnings; saving requires no errors.

- `versionCode > 0`; SDK values are non-negative and `minSdk <= targetSdk` when both exist.
- Activity and permission names are nonblank, valid Android-style component/permission names, and unique after normalization.
- `exported`/`enabled` may be absent or boolean. Activity additions warn that no DEX implementation is verified.
- Labels accept a literal or preserved raw `@...` reference. `resources.arsc` is not resolved or edited.
- Removing an activity/permission requires an explicit confirmation in UI. “Save draft” means only save staged data; UI never calls it “APK saved”. “Discard draft” deletes only the validated `work/manifest-draft.json`.

Invalid draft data is never written into an APK. v0.5 will consume only a saved, valid draft after its separate rebuild design/approval.

## Tests and fixtures

Unit-test each edit and validator rule, immutable input, normalization/duplicates, invalid SDK/version combinations, raw labels, atomic save/discard, stale-SHA rejection, and workspace containment. Add an integration test that creates a draft from an owned fixture, saves/reopens it, and verifies the original APK SHA-256 is unchanged. Provide a fixture with activity aliases/intent filters for deferred-feature regression: it must remain readable and unchanged by v0.2.

## Libraries

No library is proposed or added. The existing in-house binary parser is reused only to create drafts. A binary-AXML writer is explicitly deferred to the v0.5 rebuild design, so no writer license or JVM compatibility is claimed here.

## Implementation slices after approval

1. Models/contracts and validator tests.
2. Workspace draft store and metadata migration/tests.
3. Draft use case and read-only-to-draft transition tests.
4. Desktop editor with explicit save/discard/confirmation, then manual verification.
