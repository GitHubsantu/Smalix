package com.imdevops.smalix.engine.api

import com.imdevops.smalix.core.model.AnalysisFailure
import com.imdevops.smalix.core.model.InspectionResult
import com.imdevops.smalix.core.model.InputFormat
import com.imdevops.smalix.core.model.ResourceEntry
import com.imdevops.smalix.core.model.ResourceIndex
import com.imdevops.smalix.core.model.ResourceQuery
import com.imdevops.smalix.core.model.ResourceType
import com.imdevops.smalix.core.model.ResourceValue
import com.imdevops.smalix.core.model.ResourceDraft
import com.imdevops.smalix.core.model.ResourceDraftValidation
import com.imdevops.smalix.core.model.ProjectId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ContractsTest {
    @Test
    fun `analyzer contract can report xapk as an unsupported format`() {
        val result: InspectionResult<Nothing> = InspectionResult.Failure(
            AnalysisFailure.UnsupportedFormat(
                format = InputFormat.XAPK,
                message = "XAPK is unsupported; v0.1 accepts one standalone APK only.",
            ),
        )

        val failure = (result as InspectionResult.Failure).error as AnalysisFailure.UnsupportedFormat
        assertEquals(InputFormat.XAPK, failure.format)
    }

    @Test fun `resource search contract accepts a bounded index query`() {
        val reader = object : ResourceSearch {
            override fun find(index: ResourceIndex, query: ResourceQuery): List<ResourceEntry> = index.entries
        }
        val entry = ResourceEntry(1, "sample.app", ResourceType.STRING, "name", "", ResourceValue.Literal("value"), "resources.arsc")
        assertEquals(listOf(entry), reader.find(ResourceIndex(listOf(entry)), ResourceQuery.Text("value")))
    }

    @Test fun `resource draft validator contract remains staged only`() {
        val entry = ResourceEntry(1, "sample.app", ResourceType.COLOR, "primary", "", ResourceValue.Literal("#112233"), "resources.arsc")
        val draft = requireNotNull(ResourceDraft.from(ProjectId.random(), "a".repeat(64), entry))
        val validator = object : ResourceDraftValidator { override fun validate(draft: ResourceDraft) = ResourceDraftValidation(emptyList()) }

        assertEquals(true, validator.validate(draft).isValid)
    }
}
