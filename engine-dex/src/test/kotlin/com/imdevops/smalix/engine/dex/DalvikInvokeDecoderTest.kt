package com.imdevops.smalix.engine.dex

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class DalvikInvokeDecoderTest {
    @Test fun `decodes normal and range invoke method indexes`() {
        val result = DalvikInvokeDecoder.decode(shortArrayOf(0x6e, 7, 0, 0, 0x74, 9, 0))
        assertEquals(listOf(DecodedInvoke(7, 0, false), DecodedInvoke(9, 4, true)), result)
    }
    @Test fun `rejects truncated invoke`() { assertThrows(IllegalArgumentException::class.java) { DalvikInvokeDecoder.decode(shortArrayOf(0x6e, 7)) } }
}
