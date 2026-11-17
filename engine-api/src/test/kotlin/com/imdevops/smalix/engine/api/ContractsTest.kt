package com.imdevops.smalix.engine.api

import com.imdevops.smalix.core.model.AnalysisFailure
import com.imdevops.smalix.core.model.InspectionResult
import com.imdevops.smalix.core.model.InputFormat
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
}
