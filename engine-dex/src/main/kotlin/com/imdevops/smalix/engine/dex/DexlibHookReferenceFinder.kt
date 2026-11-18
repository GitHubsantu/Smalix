package com.imdevops.smalix.engine.dex

import com.imdevops.smalix.core.model.AnalysisFailure
import com.imdevops.smalix.core.model.HookReference
import com.imdevops.smalix.core.model.HookSignature
import com.imdevops.smalix.core.model.InspectionResult
import com.imdevops.smalix.core.model.ValidatedApk
import com.imdevops.smalix.engine.api.HookReferenceFinder
import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcode
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.reference.MethodReference

/** Read-only bounded hook lookup; it never executes or alters DEX instructions. */
class DexlibHookReferenceFinder(private val maxResults: Int = 10_000) : HookReferenceFinder {
    override suspend fun find(apk: ValidatedApk, target: HookSignature): InspectionResult<List<HookReference>> {
        return try {
        val allowed = apk.entries.map { it.normalizedPath }.filter { it.matches(Regex("classes(?:[2-9][0-9]*)?\\.dex")) }.toSet()
        val container = DexFileFactory.loadDexContainer(apk.path.toFile(), null)
        val results = mutableListOf<HookReference>()
        for (name in container.dexEntryNames.filter(allowed::contains)) {
            val dex = container.getEntry(name)?.dexFile ?: continue
            for (clazz in dex.classes) for (method in clazz.methods) {
                val implementation = method.implementation ?: continue
                var offset = 0L
                for (instruction in implementation.instructions) {
                    val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                    if (reference != null && reference.definingClass == target.classDescriptor && reference.name == target.methodName && reference.parameterTypes.joinToString(separator = "", prefix = "(", postfix = ")") == target.parameterDescriptor && reference.returnType == target.returnDescriptor) {
                        results += HookReference(name, clazz.type, method.name, method.parameterTypes.joinToString(separator = "", prefix = "(", postfix = ")") + method.returnType, target, offset, instruction.opcode.name.endsWith("_RANGE"))
                        if (results.size >= maxResults) return InspectionResult.Success(results)
                    }
                    offset += instruction.codeUnits.toLong()
                }
            }
        }
        InspectionResult.Success(results)
    } catch (error: Exception) {
        InspectionResult.Failure(AnalysisFailure.MalformedApk(error.message ?: "DEX hook scan failed."))
    }
    }
}
