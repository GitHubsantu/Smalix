package com.imdevops.smalix.engine.dex

import com.imdevops.smalix.core.model.DexClass
import com.imdevops.smalix.core.model.DexField
import com.imdevops.smalix.core.model.DexMethod
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DexSmaliRendererTest {
    @Test fun `renders indexed members without method bodies`() {
        val text = DexSmaliRenderer().render(DexClass("classes.dex", "Lsample/Main;", 1, listOf(DexField("id", "I", 2)), listOf(DexMethod("run", "()V", 1, 42))))
        assertTrue(text.contains(".class 0x1 Lsample/Main;"))
        assertTrue(text.contains(".field 0x2 id:I"))
        assertTrue(text.contains("# code offset: 42"))
    }
}
