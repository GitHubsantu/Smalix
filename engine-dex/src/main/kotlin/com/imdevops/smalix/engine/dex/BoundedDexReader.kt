package com.imdevops.smalix.engine.dex

import com.imdevops.smalix.core.model.*
import com.imdevops.smalix.engine.api.DexReader
import java.util.zip.ZipFile

/** Minimal bounded DEX class indexer. It reads descriptors only, never executes DEX. */
class BoundedDexReader(private val maxDexBytes: Int = 64 * 1024 * 1024, private val maxClasses: Int = 200_000) : DexReader {
    override suspend fun index(apk: ValidatedApk): InspectionResult<DexIndex> = try {
        val names = apk.entries.map { it.normalizedPath }.filter { it.matches(Regex("classes(?:[2-9][0-9]*)?\\.dex")) }.sortedBy { if (it == "classes.dex") 1 else it.removePrefix("classes").removeSuffix(".dex").toInt() }
        ZipFile(apk.path.toFile()).use { zip ->
            val classes = names.flatMap { name ->
                val entry = zip.getEntry(name) ?: throw IllegalArgumentException("Missing DEX entry $name.")
                if (entry.size !in 1..maxDexBytes.toLong()) throw IllegalArgumentException("DEX entry exceeds limit.")
                parse(name, zip.getInputStream(entry).readNBytes(maxDexBytes + 1))
            }
            if (classes.size > maxClasses) throw IllegalArgumentException("DEX class count exceeds limit.")
            InspectionResult.Success(DexIndex(names.map { DexEntry(it, classes.count { c -> c.dexEntryName == it }) }, classes))
        }
    } catch (e: Exception) { InspectionResult.Failure(AnalysisFailure.MalformedApk(e.message ?: "Malformed DEX.")) }

    private fun parse(name: String, b: ByteArray): List<DexClass> {
        require(b.size >= 112 && String(b, 0, 4) == "dex\n") { "Invalid DEX header." }
        fun u(o: Int): Int { require(o >= 0 && o + 4 <= b.size) { "DEX offset is outside entry." }; return (b[o].toInt() and 255) or ((b[o+1].toInt() and 255) shl 8) or ((b[o+2].toInt() and 255) shl 16) or ((b[o+3].toInt() and 255) shl 24) }
        val stringN=u(56); val stringO=u(60); val typeN=u(64); val typeO=u(68); val classN=u(96); val classO=u(100)
        require(classN <= maxClasses && stringN <= 1_000_000 && typeN <= 1_000_000) { "DEX table exceeds limit." }
        fun string(i:Int):String { val p=u(stringO+i*4); var x=p; while (x<b.size && (b[x++].toInt() and 128)!=0){}; val start=x; while(x<b.size && b[x].toInt()!=0)x++; require(x<b.size){"Unterminated DEX string."}; return b.copyOfRange(start,x).toString(Charsets.UTF_8) }
        fun type(i:Int)=string(u(typeO+i*4))
        return (0 until classN).map { i -> val o=classO+i*32; DexClass(name,type(u(o)),u(o+4),emptyList(),emptyList()) }
    }
}
