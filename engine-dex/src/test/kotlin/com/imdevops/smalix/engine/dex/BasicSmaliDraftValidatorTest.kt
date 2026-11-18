package com.imdevops.smalix.engine.dex

import com.imdevops.smalix.core.model.ProjectId
import com.imdevops.smalix.core.model.SmaliDraft
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class BasicSmaliDraftValidatorTest {
    @Test fun `accepts bounded method text and rejects malformed text`() {
        val draft = SmaliDraft(UUID.randomUUID(), ProjectId.random(), "a".repeat(64), "classes.dex", "Lsample/C;", "run", "()V", 0, ".method public run()V\n.end method")
        assertTrue(BasicSmaliDraftValidator().validate(draft).isValid)
        assertFalse(BasicSmaliDraftValidator().validate(draft.copy(text = "not smali")).isValid)
    }
}
