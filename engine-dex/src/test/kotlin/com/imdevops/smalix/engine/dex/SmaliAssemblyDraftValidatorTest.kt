package com.imdevops.smalix.engine.dex

import com.imdevops.smalix.core.model.ProjectId
import com.imdevops.smalix.core.model.SmaliDraft
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class SmaliAssemblyDraftValidatorTest {
    @Test fun `assembles valid staged method only in temporary output`() {
        assertTrue(SmaliAssemblyDraftValidator().validate(draft(".method public static run()V\n    .registers 0\n    return-void\n.end method")).isValid)
    }

    @Test fun `returns validation error for invalid staged method`() {
        assertFalse(SmaliAssemblyDraftValidator().validate(draft(".method public static run()V\n.end method")).isValid)
    }

    private fun draft(text: String) = SmaliDraft(UUID.randomUUID(), ProjectId.random(), "a".repeat(64), "classes.dex", "Lsample/C;", "run", "()V", 0, text)
}
