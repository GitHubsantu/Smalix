package com.imdevops.smalix.engine.dex

import com.imdevops.smalix.core.model.DexClass

/** Renders indexed metadata as bounded, read-only Smali-like text; it does not decode method bodies. */
class DexSmaliRenderer(private val maxCharacters: Int = 256_000) {
    fun render(clazz: DexClass): String = buildString {
        append("# Read-only indexed view; method bodies are not decoded.\n")
        append(".class 0x").append(clazz.accessFlags.toString(16)).append(' ').append(clazz.descriptor).append("\n\n")
        clazz.fields.forEach { append(".field 0x").append(it.accessFlags.toString(16)).append(' ').append(it.name).append(':').append(it.typeDescriptor).append('\n'); bounded() }
        if (clazz.fields.isNotEmpty()) append('\n')
        clazz.methods.forEach { append(".method 0x").append(it.accessFlags.toString(16)).append(' ').append(it.name).append(it.descriptor).append("\n    # code offset: ").append(it.codeOffset ?: "none").append("\n.end method\n\n"); bounded() }
    }.also { require(it.length <= maxCharacters) { "Rendered Smali exceeds limit." } }

    private fun StringBuilder.bounded() { require(length <= maxCharacters) { "Rendered Smali exceeds limit." } }
}
