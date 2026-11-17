package com.imdevops.smalix

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.imdevops.smalix.core.database.SqliteProjectRepository
import com.imdevops.smalix.core.model.InspectionResult
import com.imdevops.smalix.core.model.InspectionSummary
import com.imdevops.smalix.core.model.Project
import com.imdevops.smalix.core.model.ZipEntryInfo
import com.imdevops.smalix.core.storage.FileSystemWorkspaceStore
import com.imdevops.smalix.engine.apk.ZipApkAnalyzer
import com.imdevops.smalix.engine.dex.ZipDexDetector
import com.imdevops.smalix.engine.manifest.BinaryManifestParser
import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Path
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "Smalix") { MaterialTheme { SmalixScreen() } }
}

@Composable
private fun SmalixScreen() {
    val scope = rememberCoroutineScope()
    val appData = remember { Path.of(System.getProperty("user.home"), ".smalix") }
    val repository = remember { SqliteProjectRepository(appData.resolve("metadata.db")) }
    val workspaceStore = remember { FileSystemWorkspaceStore(appData.resolve("files/projects")) }
    val inspection = remember { InspectionUseCase(ZipApkAnalyzer(), BinaryManifestParser(), ZipDexDetector()) }
    val createProject = remember { ProjectCreationUseCase(repository, workspaceStore) }
    val reopenInspection = remember { ReopenProjectInspectionUseCase(repository, workspaceStore, ProjectInspectionUseCase(repository, inspection)) }
    var projects by remember { mutableStateOf(emptyList<Project>()) }
    var selected by remember { mutableStateOf<Project?>(null) }
    var status by remember { mutableStateOf("Choose an APK to create a read-only project.") }
    var summary by remember { mutableStateOf<InspectionSummary?>(null) }
    var busy by remember { mutableStateOf(false) }
    fun refresh() = scope.launch { projects = withContext(Dispatchers.IO) { repository.list() }; selected = projects.firstOrNull { it.id == selected?.id } ?: projects.firstOrNull() }
    LaunchedEffect(Unit) { projects = withContext(Dispatchers.IO) { repository.list() }; selected = projects.firstOrNull() }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy, onClick = { chooseApk()?.let { source -> scope.launch {
                busy = true; status = "Creating project…"; summary = null
                runCatching { withContext(Dispatchers.IO) { createProject.create(source) } }
                    .onSuccess { created -> selected = created.project; status = "Project created. Select Inspect."; refresh() }
                    .onFailure { status = "Import failed: ${it.message ?: "unknown error"}" }
                busy = false
            } } }) { Text("Import APK") }
            Button(enabled = !busy && selected != null, onClick = { val project = selected ?: return@Button; scope.launch {
                busy = true; status = "Inspecting immutable original.apk…"; summary = null
                when (val result = withContext(Dispatchers.IO) { reopenInspection.inspect(project.id, Instant.now().plusSeconds(120)) }) {
                    ReopenInspectionResult.ProjectNotFound -> status = "Project no longer exists."
                    is ReopenInspectionResult.WorkspaceUnavailable -> status = "Workspace unavailable: ${result.message}"
                    is ReopenInspectionResult.Completed -> when (val value = result.result) {
                        is InspectionResult.Success -> { summary = value.value; status = "Inspection complete."; refresh() }
                        is InspectionResult.Failure -> { status = "Inspection failed: ${value.error}"; refresh() }
                    }
                }; busy = false
            } }) { Text("Inspect selected") }
            Button(enabled = !busy, onClick = ::refresh) { Text("Refresh") }
        }
        Text(status)
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LazyColumn(Modifier.width(300.dp)) { items(projects, key = { it.id.value }) { project ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) { Button(onClick = { selected = project }, modifier = Modifier.fillMaxWidth()) { Text("${project.displayName} — ${project.analysisStatus}") } }
            } }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                selected?.let { Text("Selected: ${it.displayName}\nSHA-256: ${it.originalSha256 ?: "not analyzed"}") }
                if (summary != null) SummaryPanel(summary!!) else Text("No in-memory inspection result yet.")
            }
        }
    }
}

@Composable
private fun SummaryPanel(summary: InspectionSummary) {
    InspectorSection(
        title = "APK metadata",
        content = """
            Package: ${summary.manifest.packageName}
            Version: ${summary.manifest.versionName ?: "—"} (${summary.manifest.versionCode ?: "—"})
            SDK: ${summary.manifest.minSdk ?: "—"} / ${summary.manifest.targetSdk ?: "—"}
            Label: ${summary.manifest.label ?: "—"}
        """.trimIndent(),
    )
    InspectorSection(title = "DEX", content = summary.dex.entries.joinToString().ifBlank { "No DEX entries" })
    InspectorSection(
        title = "Activities",
        content = summary.manifest.activities.joinToString(separator = "\n") { it.name }.ifBlank { "No activities declared" },
    )
    InspectorSection(
        title = "Permissions",
        content = summary.manifest.permissions.joinToString(separator = "\n") { it.name }.ifBlank { "No permissions declared" },
    )
    ArchiveFilesSection(summary.apk.archive.entries)
}

@Composable
private fun InspectorSection(title: String, content: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(content)
        }
    }
}

@Composable
private fun ArchiveFilesSection(entries: List<ZipEntryInfo>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Archive files", style = MaterialTheme.typography.titleMedium)
            Text("${entries.size} validated ZIP entries. Read-only inventory; files are not extracted.")
            LazyColumn(Modifier.fillMaxWidth().height(240.dp)) {
                items(entries.sortedBy(ZipEntryInfo::normalizedPath), key = ZipEntryInfo::normalizedPath) { entry ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(entry.normalizedPath, Modifier.weight(1f))
                        Text("${entry.compressedSizeBytes} B / ${entry.uncompressedSizeBytes} B")
                    }
                }
            }
        }
    }
}

private fun chooseApk(): Path? {
    val dialog = FileDialog(null as Frame?, "Select APK", FileDialog.LOAD)
    dialog.isVisible = true
    return dialog.file?.let { Path.of(dialog.directory, it) }
}
