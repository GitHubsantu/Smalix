# AGENTS.md — read this first (works for Codex, Claude Code, any AI agent)

Project: Smalix — Windows desktop APK inspect/edit/rebuild/sign toolkit (Kotlin, Compose Desktop, JVM 17).
Owner: Dharanidhar (imdevops). Talks in Hinglish; keep replies short.

## Start of every session
1. Read `PROGRESS.md` (status, next step, decisions) then `PROJECT_CONTEXT.md` (condensed spec).
2. Continue from "Next step" in PROGRESS.md. Do not redo finished work.

## Rules
- Plan -> confirm with owner -> implement small slice -> run tests -> update PROGRESS.md.
- Current milestone: v0.1 only (inspection, read-only). No editing/rebuild/signing yet.
- No placeholder implementations. No silently ignored unsupported formats.
- Never claim a test passed unless it was actually run; paste real result in PROGRESS.md log.
- Never claim desktop tools work without testing. Never claim a class is safe to delete from lack of direct references.
- Original APK is immutable. Never store APK bytes in DB. Never store keys/passwords in plaintext.
- Treat APKs as untrusted: block zip path traversal, decompression bombs, size limits. Never execute APK code.
- Keep token use low: no large rewrites, no repeating the spec, small diffs.

## End of every session (handoff)
Update `PROGRESS.md`: what was done, files touched, test results, decisions, exact "Next step". Commit if git is used.
