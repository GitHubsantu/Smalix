package com.imdevops.smalix.engine.dex

import com.imdevops.smalix.core.model.SmaliDraft
import com.imdevops.smalix.core.model.SmaliValidation
import com.imdevops.smalix.engine.api.SmaliDraftValidator
import org.jf.smali.Smali
import org.jf.smali.SmaliOptions
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

/** Validates staged method text by assembling only a store-owned temporary DEX, then deleting it. */
class SmaliAssemblyDraftValidator : SmaliDraftValidator {
    override fun validate(draft: SmaliDraft): SmaliValidation {
        val local = BasicSmaliDraftValidator().validate(draft)
        if (!local.isValid) return local
        val directory = Files.createTempDirectory("smalix-smali-check-")
        return try {
            val source = directory.resolve("draft.smali")
            val output = directory.resolve("validated.dex")
            Files.writeString(source, ".class public ${draft.classDescriptor}\n.super Ljava/lang/Object;\n\n${draft.text}\n")
            val options = SmaliOptions().apply { outputDexFile = output.toString(); apiLevel = 26; verboseErrors = false }
            if (Smali.assemble(options, source.toString()) && Files.isRegularFile(output)) SmaliValidation(emptyList())
            else SmaliValidation(listOf("Smali assembly validation failed."))
        } catch (error: Exception) {
            SmaliValidation(listOf("Smali assembly validation failed: ${error.message ?: error.javaClass.simpleName}"))
        } finally {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
}
