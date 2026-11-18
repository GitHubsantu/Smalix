package com.imdevops.smalix.engine.manifest

import com.imdevops.smalix.core.model.ResourceDraft
import com.imdevops.smalix.core.model.ResourceDraftValidation
import com.imdevops.smalix.core.model.ResourceType
import com.imdevops.smalix.engine.api.ResourceDraftValidator

/** Lexical validation for staged resource text. It never writes a resource table or APK. */
class DefaultResourceDraftValidator : ResourceDraftValidator {
    override fun validate(draft: ResourceDraft): ResourceDraftValidation {
        val message = when (draft.type) {
            ResourceType.STRING -> stringError(draft.proposedValue)
            ResourceType.COLOR -> colorError(draft.proposedValue)
            ResourceType.DIMEN -> dimenError(draft.proposedValue)
        }
        return ResourceDraftValidation(listOfNotNull(message))
    }

    private fun stringError(value: String): String? = when {
        value.length > MAX_STRING_CHARS -> "String value exceeds $MAX_STRING_CHARS characters."
        value.any { it == '\u0000' } -> "String value must not contain NUL."
        hasUnpairedSurrogate(value) -> "String value contains an unpaired UTF-16 surrogate."
        else -> null
    }

    private fun colorError(value: String): String? = if (COLOR.matches(value)) null else "Color must be #RRGGBB or #AARRGGBB."

    private fun dimenError(value: String): String? {
        val match = DIMEN.matchEntire(value) ?: return "Dimension must be a finite decimal followed by dp, sp, px, pt, in, or mm."
        return if (match.groupValues[1].toDoubleOrNull()?.isFinite() == true) null else "Dimension must be finite."
    }

    private fun hasUnpairedSurrogate(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char.isHighSurrogate()) {
                if (index + 1 == value.length || !value[index + 1].isLowSurrogate()) return true
                index += 2
            } else {
                if (char.isLowSurrogate()) return true
                index++
            }
        }
        return false
    }

    private companion object {
        const val MAX_STRING_CHARS = 65_536
        val COLOR = Regex("^#(?:[0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})$")
        val DIMEN = Regex("^([+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+))(dp|sp|px|pt|in|mm)$")
    }
}
