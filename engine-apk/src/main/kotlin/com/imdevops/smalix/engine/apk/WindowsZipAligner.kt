package com.imdevops.smalix.engine.apk

import com.imdevops.smalix.core.model.ZipAlignmentRequest
import com.imdevops.smalix.core.model.ZipAlignmentResult
import com.imdevops.smalix.engine.api.ZipAligner
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Windows `zipalign.exe` adapter for a distinct, build-local output. It never overwrites an APK. */
class WindowsZipAligner(
    private val executable: Path,
    private val maximumDuration: Duration = Duration.ofMinutes(2),
) : ZipAligner {
    init { require(!maximumDuration.isNegative && !maximumDuration.isZero) }

    override suspend fun align(request: ZipAlignmentRequest): ZipAlignmentResult {
        if (!Files.isRegularFile(executable)) return ZipAlignmentResult.Rejected("zipalign executable is unavailable.")
        if (expired(request.deadline)) return ZipAlignmentResult.Failed("ZIP alignment exceeded its deadline.")
        val alignmentDeadline = Instant.now().plus(maximumDuration).let { cap ->
            if (request.deadline.isBefore(cap)) request.deadline else cap
        }

        val input = request.input.path.toAbsolutePath().normalize()
        val output = request.outputPath.toAbsolutePath().normalize()
        if (!Files.isRegularFile(input)) return ZipAlignmentResult.Rejected("Validated build output is unavailable for alignment.")
        if (input == output || output.parent == null || !Files.isDirectory(output.parent)) {
            return ZipAlignmentResult.Rejected("ZIP alignment requires a distinct output in an existing directory.")
        }
        if (Files.exists(output)) return ZipAlignmentResult.Rejected("Aligned APK output already exists.")
        val size = try {
            Files.size(input)
        } catch (_: IOException) {
            return ZipAlignmentResult.Rejected("Validated build output is unavailable for alignment.")
        }
        if (size != request.input.archiveSizeBytes) return ZipAlignmentResult.Rejected("Build output changed after validation.")

        val temporary = output.parent.resolve(".${output.fileName}.${UUID.randomUUID()}.zipalign.tmp")
        return try {
            when (val alignment = runTool(listOf("-v", "4", input.toString(), temporary.toString()), alignmentDeadline, output.parent)) {
                is ToolResult.Failed -> ZipAlignmentResult.Failed(alignment.message)
                ToolResult.Completed -> when (val verification = runTool(listOf("-c", "-v", "4", temporary.toString()), alignmentDeadline, output.parent)) {
                    is ToolResult.Failed -> ZipAlignmentResult.Failed(verification.message)
                    ToolResult.Completed -> moveWithoutOverwrite(temporary, output)
                }
            }
        } finally {
            try {
                Files.deleteIfExists(temporary)
            } catch (_: IOException) {
                // The unique temporary path is build-local; a later workspace cleanup can remove a failed tool artifact.
            }
        }
    }

    private fun runTool(arguments: List<String>, deadline: Instant, directory: Path): ToolResult {
        val waitMillis = waitMillis(deadline) ?: return ToolResult.Failed("ZIP alignment exceeded its deadline.")
        val process = try {
            ProcessBuilder(listOf(executable.toString()) + arguments)
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
        } catch (exception: IOException) {
            return ToolResult.Failed("Unable to start zipalign: ${exception.message ?: "I/O error"}")
        } catch (_: SecurityException) {
            return ToolResult.Failed("Starting zipalign was denied.")
        }
        return try {
            if (!process.waitFor(waitMillis, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                process.waitFor()
                ToolResult.Failed("ZIP alignment exceeded its deadline.")
            } else if (process.exitValue() != 0) {
                ToolResult.Failed("zipalign failed with exit code ${process.exitValue()}.")
            } else {
                ToolResult.Completed
            }
        } catch (_: InterruptedException) {
            process.destroyForcibly()
            Thread.currentThread().interrupt()
            ToolResult.Failed("ZIP alignment was interrupted.")
        }
    }

    private fun moveWithoutOverwrite(temporary: Path, output: Path): ZipAlignmentResult = try {
        Files.move(temporary, output)
        ZipAlignmentResult.Created(output)
    } catch (_: FileAlreadyExistsException) {
        ZipAlignmentResult.Rejected("Aligned APK output already exists.")
    } catch (exception: IOException) {
        ZipAlignmentResult.Failed("Unable to create aligned APK: ${exception.message ?: "I/O error"}")
    }

    private fun waitMillis(deadline: Instant): Long? {
        val now = Instant.now()
        if (!now.isBefore(deadline)) return null
        val cappedDeadline = now.plus(maximumDuration).let { cap -> if (deadline.isBefore(cap)) deadline else cap }
        return Duration.between(now, cappedDeadline).toMillis().coerceAtLeast(1)
    }

    private fun expired(deadline: Instant): Boolean = !Instant.now().isBefore(deadline)

    private sealed interface ToolResult {
        data object Completed : ToolResult
        data class Failed(val message: String) : ToolResult
    }
}
