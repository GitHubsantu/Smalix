package com.imdevops.smalix.engine.dex

internal data class DecodedInvoke(val methodIndex: Int, val codeUnitOffset: Int, val range: Boolean)

/** Decodes only fixed-width Dalvik invoke formats 35c and 3rc from already bounded code units. */
internal object DalvikInvokeDecoder {
    fun decode(codeUnits: ShortArray, maxInstructions: Int = 100_000): List<DecodedInvoke> {
        require(codeUnits.size <= maxInstructions) { "Code item exceeds instruction limit." }
        val result = mutableListOf<DecodedInvoke>()
        var offset = 0
        while (offset < codeUnits.size) {
            val first = codeUnits[offset].toInt() and 0xffff
            when (first and 0xff) {
                in 0x6e..0x72 -> { require(offset + 2 < codeUnits.size) { "Truncated invoke instruction." }; result += DecodedInvoke(codeUnits[offset + 1].toInt() and 0xffff, offset, false); offset += 3 }
                in 0x74..0x78 -> { require(offset + 2 < codeUnits.size) { "Truncated invoke-range instruction." }; result += DecodedInvoke(codeUnits[offset + 1].toInt() and 0xffff, offset, true); offset += 3 }
                else -> offset++
            }
        }
        return result
    }
}
