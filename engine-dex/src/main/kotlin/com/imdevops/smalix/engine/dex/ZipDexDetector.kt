package com.imdevops.smalix.engine.dex

import com.imdevops.smalix.core.model.AnalysisFailure
import com.imdevops.smalix.core.model.DexInfo
import com.imdevops.smalix.core.model.InspectionResult
import com.imdevops.smalix.core.model.ValidatedApk
import com.imdevops.smalix.engine.api.DexDetector

class ZipDexDetector : DexDetector {
    override suspend fun detect(apk: ValidatedApk): InspectionResult<DexInfo> {
        val dexEntries = mutableMapOf<Int, String>()
        for (entry in apk.entries) {
            if (!looksLikeDex(entry.normalizedPath)) continue
            val dexIndex = dexIndex(entry.normalizedPath)
                ?: return malformed("Unsupported DEX entry name: ${entry.normalizedPath}")
            if (dexEntries.put(dexIndex, entry.normalizedPath) != null) {
                return malformed("Duplicate DEX index $dexIndex.")
            }
        }

        if (dexEntries.isEmpty()) return InspectionResult.Success(DexInfo(emptyList()))
        val highestIndex = dexEntries.keys.max()
        for (index in 1..highestIndex) {
            if (index !in dexEntries) return malformed("Missing classes${if (index == 1) "" else index}.dex in multidex sequence.")
        }
        return InspectionResult.Success(DexInfo((1..highestIndex).map(dexEntries::getValue)))
    }

    private fun dexIndex(path: String): Int? {
        if (path == PRIMARY_DEX) return 1
        return SECONDARY_DEX.matchEntire(path)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun looksLikeDex(path: String): Boolean = path.startsWith("classes") && path.endsWith(".dex")

    private fun malformed(message: String): InspectionResult.Failure =
        InspectionResult.Failure(AnalysisFailure.MalformedApk(message))

    private companion object {
        const val PRIMARY_DEX = "classes.dex"
        val SECONDARY_DEX = Regex("classes([2-9][0-9]*)\\.dex")
    }
}
