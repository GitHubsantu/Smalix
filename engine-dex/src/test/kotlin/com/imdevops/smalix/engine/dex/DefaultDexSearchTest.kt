package com.imdevops.smalix.engine.dex

import com.imdevops.smalix.core.model.DexClass
import com.imdevops.smalix.core.model.DexClassQuery
import com.imdevops.smalix.core.model.DexEntry
import com.imdevops.smalix.core.model.DexIndex
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DefaultDexSearchTest {
    private val index = DexIndex(
        listOf(DexEntry("classes.dex", 3)),
        listOf(
            DexClass("classes.dex", "Lsample/Main;", 0, emptyList(), emptyList()),
            DexClass("classes.dex", "Lsample/internal/Helper;", 0, emptyList(), emptyList()),
            DexClass("classes.dex", "Lother/Thing;", 0, emptyList(), emptyList()),
        ),
    )

    @Test fun `finds exact class descriptor`() {
        assertEquals(listOf("Lsample/Main;"), DefaultDexSearch().find(index, DexClassQuery.ExactDescriptor("Lsample/Main;")).map(DexClass::descriptor))
    }

    @Test fun `finds package and child packages only`() {
        assertEquals(listOf("Lsample/Main;", "Lsample/internal/Helper;"), DefaultDexSearch().find(index, DexClassQuery.PackagePrefix("sample")).map(DexClass::descriptor))
    }
}
