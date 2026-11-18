package com.imdevops.smalix.engine.dex

import com.imdevops.smalix.core.model.HookSignature
import com.imdevops.smalix.core.model.InspectionResult
import com.imdevops.smalix.core.model.ValidatedApk
import com.imdevops.smalix.core.model.ZipEntryInfo
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

class InShotHookFixtureIntegrationTest {
    @Test fun `scans supplied InShot APK for Customdialog zenx hook`() = runBlocking {
        val path = System.getenv("SMALIX_INSHOT_FIXTURE")?.let(Path::of) ?: Path.of("D:/Piratemods.com-InShot_v2.200.1530.apk")
        assumeTrue(Files.isRegularFile(path), "InShot fixture is unavailable")
        val entries = ZipFile(path.toFile()).use { zip -> zip.entries().asSequence().filter { it.name.matches(Regex("classes(?:[2-9][0-9]*)?\\.dex")) }.map { ZipEntryInfo(it.name, it.compressedSize, it.size) }.toList() }
        val result = DexlibHookReferenceFinder().find(ValidatedApk(path, "0".repeat(64), Files.size(path), entries), HookSignature("Lcom/Epic/custom/dialog/Customdialog;", "zenx", "(Landroid/app/Activity;)", "V"))
        val success = assertInstanceOf(InspectionResult.Success::class.java, result) as InspectionResult.Success<List<com.imdevops.smalix.core.model.HookReference>>
        println("InShot Customdialog.zenx hook matches: ${success.value.size}")
    }
}
