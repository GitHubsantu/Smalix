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
import com.imdevops.smalix.core.model.BuildRequest
import com.imdevops.smalix.core.model.BuildResult
import com.imdevops.smalix.core.model.RelativePath
import com.imdevops.smalix.core.model.Workspace
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.coroutines.startCoroutine

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

    @Test fun `build pipeline contract has no implicit APK mutation`() {
        val pipeline = object : BuildPipeline { override suspend fun build(workspace: Workspace, request: BuildRequest) = BuildResult.Rejected("not implemented") }
        val workspace = Workspace(ProjectId.random(), java.nio.file.Path.of("p"), java.nio.file.Path.of("p/original.apk"), java.nio.file.Path.of("p/work"), java.nio.file.Path.of("p/logs"))
        var result: BuildResult? = null
        suspend { pipeline.build(workspace, BuildRequest(java.util.UUID.randomUUID(), workspace.projectId, "a".repeat(64), RelativePath.of("exports/result.apk"), java.time.Instant.EPOCH)) }.startCoroutine(object : kotlin.coroutines.Continuation<BuildResult> {
            override val context = kotlin.coroutines.EmptyCoroutineContext
            override fun resumeWith(value: Result<BuildResult>) { result = value.getOrThrow() }
        })
        assertEquals(BuildResult.Rejected("not implemented"), result)
    }
}
