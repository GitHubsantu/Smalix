package com.imdevops.smalix.engine.manifest

import com.imdevops.smalix.core.model.ResourceEntry
import com.imdevops.smalix.core.model.ResourceIndex
import com.imdevops.smalix.core.model.ResourceQuery
import com.imdevops.smalix.core.model.ResourceType
import com.imdevops.smalix.core.model.ResourceValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DefaultResourceSearchTest {
    private val title = ResourceEntry(1, "sample.app", ResourceType.STRING, "title", "", ResourceValue.Literal("Welcome"), "resources.arsc")
    private val color = ResourceEntry(2, "sample.app", ResourceType.COLOR, "primary", "", ResourceValue.Literal("#112233"), "resources.arsc")

    @Test fun `finds exact qualified resource name`() {
        assertEquals(listOf(title), DefaultResourceSearch().find(ResourceIndex(listOf(title, color)), ResourceQuery.ExactName("sample.app", ResourceType.STRING, "title")))
    }

    @Test fun `finds literal value text without matching raw references`() {
        val reference = ResourceEntry(3, "sample.app", ResourceType.STRING, "linked", "", ResourceValue.Reference("@string/title"), "resources.arsc")
        assertEquals(listOf(title), DefaultResourceSearch().find(ResourceIndex(listOf(title, color, reference)), ResourceQuery.Text("welcome")))
    }
}
