package com.imdevops.smalix.engine.dex

import com.imdevops.smalix.core.model.SmaliDraft
import com.imdevops.smalix.core.model.SmaliValidation
import com.imdevops.smalix.engine.api.SmaliDraftValidator

/** Bounded text checks only; this is not an assembler or a full Smali parser. */
class BasicSmaliDraftValidator : SmaliDraftValidator {
    override fun validate(draft: SmaliDraft): SmaliValidation = buildList {
        if (draft.text.isBlank()) add("Staged Smali text is empty.")
        if (draft.text.length > MAX_TEXT_CHARACTERS) add("Staged Smali text exceeds $MAX_TEXT_CHARACTERS characters.")
        if ('\u0000' in draft.text) add("Staged Smali text contains a NUL character.")
        if (!draft.text.trimStart().startsWith(".method")) add("Staged Smali text must start with a .method declaration.")
        if (!draft.text.trimEnd().endsWith(".end method")) add("Staged Smali text must end with .end method.")
    }.let(::SmaliValidation)

    private companion object { const val MAX_TEXT_CHARACTERS = 256_000 }
}
