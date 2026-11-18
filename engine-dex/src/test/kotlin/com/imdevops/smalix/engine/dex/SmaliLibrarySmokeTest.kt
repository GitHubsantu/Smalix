package com.imdevops.smalix.engine.dex

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class SmaliLibrarySmokeTest {
    @Test fun `smali library loads on JDK 17`() {
        assertNotNull(Class.forName("org.jf.smali.Smali"))
    }
}
