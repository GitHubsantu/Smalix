package com.imdevops.smalix.engine.manifest

import com.imdevops.smalix.core.model.ProjectId
import com.imdevops.smalix.core.model.ResourceDraft
import com.imdevops.smalix.core.model.ResourceEntry
import com.imdevops.smalix.core.model.ResourceType
import com.imdevops.smalix.core.model.ResourceValue
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DefaultResourceDraftValidatorTest {
    private val validator = DefaultResourceDraftValidator()

    @Test fun `accepts bounded UTF8 text and supported literal formats`() {
        assertTrue(validator.validate(draft(ResourceType.STRING, "Hello \uD83D\uDE03")).isValid)
        assertTrue(validator.validate(draft(ResourceType.COLOR, "#80A0B0C0")).isValid)
        assertTrue(validator.validate(draft(ResourceType.DIMEN, "-1.5dp")).isValid)
    }

    @Test fun `rejects invalid colors dimens and malformed text`() {
        assertFalse(validator.validate(draft(ResourceType.COLOR, "#12345")).isValid)
        assertFalse(validator.validate(draft(ResourceType.DIMEN, "Infinitydp")).isValid)
        assertFalse(validator.validate(draft(ResourceType.DIMEN, "12em")).isValid)
        assertFalse(validator.validate(draft(ResourceType.STRING, "bad\u0000text")).isValid)
        assertFalse(validator.validate(draft(ResourceType.STRING, "\uD800")).isValid)
    }

    private fun draft(type: ResourceType, value: String): ResourceDraft = requireNotNull(
        ResourceDraft.from(
            ProjectId.random(), "a".repeat(64),
            ResourceEntry(0x7f01_0001, "sample.app", type, "value", "", ResourceValue.Literal("old"), "resources.arsc"),
        ),
    ).copy(proposedValue = value)
}
