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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
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
import com.imdevops.smalix.core.model.ManifestDraft
import com.imdevops.smalix.core.model.ManifestEdit
import com.imdevops.smalix.core.model.ManifestLabel
import com.imdevops.smalix.core.model.ActivityInfo
import com.imdevops.smalix.core.model.PermissionInfo
import com.imdevops.smalix.core.model.DexIndex
import com.imdevops.smalix.core.model.Project
import com.imdevops.smalix.core.model.ZipEntryInfo
import com.imdevops.smalix.core.storage.FileSystemWorkspaceStore
import com.imdevops.smalix.core.storage.FileSystemManifestDraftStore
import com.imdevops.smalix.engine.apk.ZipApkAnalyzer
import com.imdevops.smalix.engine.dex.ZipDexDetector
import com.imdevops.smalix.engine.dex.BoundedDexReader
import com.imdevops.smalix.engine.dex.DefaultDexSearch
import com.imdevops.smalix.core.model.DexClassQuery
import com.imdevops.smalix.engine.manifest.BinaryManifestParser
import com.imdevops.smalix.engine.manifest.DefaultManifestDraftEditor
import com.imdevops.smalix.engine.manifest.DefaultManifestDraftValidator
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
    val drafts = remember { ManifestDraftUseCase(repository, workspaceStore, FileSystemManifestDraftStore(workspaceStore), repository, inspection, DefaultManifestDraftEditor(), DefaultManifestDraftValidator()) }
    var projects by remember { mutableStateOf(emptyList<Project>()) }
    var selected by remember { mutableStateOf<Project?>(null) }
    var status by remember { mutableStateOf("Choose an APK to create a read-only project.") }
    var summary by remember { mutableStateOf<InspectionSummary?>(null) }
    var dexIndex by remember { mutableStateOf<DexIndex?>(null) }
    var dexSearch by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf<ManifestDraft?>(null) }
    var draftLabel by remember { mutableStateOf("") }
    var draftVersionName by remember { mutableStateOf("") }
    var draftVersionCode by remember { mutableStateOf("") }
    var draftMinSdk by remember { mutableStateOf("") }
    var draftTargetSdk by remember { mutableStateOf("") }
    var newActivity by remember { mutableStateOf("") }
    var updateActivityName by remember { mutableStateOf("") }
    var updateActivityExported by remember { mutableStateOf("") }
    var updateActivityEnabled by remember { mutableStateOf("") }
    var newPermission by remember { mutableStateOf("") }
    var removalName by remember { mutableStateOf("") }
    var removalIsActivity by remember { mutableStateOf(true) }
    var removalConfirmation by remember { mutableStateOf(false) }
    var draftValidation by remember { mutableStateOf("") }
    var discardConfirmation by remember { mutableStateOf(false) }
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
            Button(enabled = !busy && selected != null, onClick = { val project = selected ?: return@Button; scope.launch {
                busy = true; status = "Opening staged manifest draft..."
                when (val result = withContext(Dispatchers.IO) { drafts.openOrCreate(project.id, Instant.now().plusSeconds(120)) }) {
                    is DraftSessionResult.Ready -> { draft = result.draft; draftLabel = when (val label = result.draft.label) { is ManifestLabel.Literal -> label.value; is ManifestLabel.ResourceReference -> label.value; null -> "" }; draftVersionName = result.draft.versionName.orEmpty(); draftVersionCode = result.draft.versionCode?.toString().orEmpty(); draftMinSdk = result.draft.minSdk?.toString().orEmpty(); draftTargetSdk = result.draft.targetSdk?.toString().orEmpty(); draftValidation = result.validation.issues.joinToString("\n") { it.message }; status = "Draft saved under work/. original.apk is unchanged." }
                    DraftSessionResult.ProjectNotFound -> status = "Project no longer exists."
                    DraftSessionResult.ProjectNotReady -> status = "Inspect successfully before editing a draft."
                    is DraftSessionResult.WorkspaceUnavailable -> status = "Workspace unavailable: ${result.message}"
                    is DraftSessionResult.StaleDraft -> status = "Draft unavailable: ${result.message}"
                    is DraftSessionResult.InspectionFailed -> status = "Draft inspection failed: ${result.error}"
                }; busy = false
            } }) { Text("Open manifest draft") }
            Button(enabled = !busy && summary != null, onClick = { val value = summary ?: return@Button; scope.launch {
                busy = true; status = "Indexing validated DEX classes..."
                when (val result = withContext(Dispatchers.IO) { BoundedDexReader().index(value.apk.archive) }) {
                    is InspectionResult.Success -> { dexIndex = result.value; status = "Read-only DEX index loaded (${result.value.classes.size} classes)." }
                    is InspectionResult.Failure -> status = "DEX index failed: ${result.error}"
                }; busy = false
            } }) { Text("Browse DEX classes") }
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
                dexIndex?.let { index ->
                    OutlinedTextField(dexSearch, { dexSearch = it }, Modifier.fillMaxWidth(), label = { Text("DEX class/package search (exact Ldescriptor; supported)") }, enabled = !busy)
                    val term = dexSearch.trim()
                    val found = when {
                        term.isBlank() -> index.classes
                        term.startsWith("L") && term.endsWith(";") -> DefaultDexSearch().find(index, DexClassQuery.ExactDescriptor(term))
                        term.startsWith("L") -> index.classes.filter { it.descriptor.startsWith(term, ignoreCase = true) }
                        else -> index.classes.filter { it.packageName.startsWith(term, ignoreCase = true) || it.descriptor.contains(term, ignoreCase = true) }
                    }
                    InspectorSection("DEX classes (${found.size})", found.take(500).joinToString("\n") { it.descriptor }.ifBlank { "No matching classes" } + if (found.size > 500) "\nShowing first 500 results." else "")
                }
                draft?.let { InspectorSection("Staged manifest draft", "Revision ${it.revision}. Label: ${it.label ?: "none"}\nVersion: ${it.versionName ?: "none"} (${it.versionCode ?: "none"})\nThis is staged data only; original.apk is unchanged.") }
                draft?.let { current ->
                    OutlinedTextField(draftLabel, { draftLabel = it }, Modifier.fillMaxWidth(), label = { Text("App label") }, enabled = !busy)
                    OutlinedTextField(draftVersionName, { draftVersionName = it }, Modifier.fillMaxWidth(), label = { Text("Version name") }, enabled = !busy)
                    OutlinedTextField(draftVersionCode, { draftVersionCode = it }, Modifier.fillMaxWidth(), label = { Text("Version code") }, enabled = !busy)
                    OutlinedTextField(draftMinSdk, { draftMinSdk = it }, Modifier.fillMaxWidth(), label = { Text("Minimum SDK") }, enabled = !busy)
                    OutlinedTextField(draftTargetSdk, { draftTargetSdk = it }, Modifier.fillMaxWidth(), label = { Text("Target SDK") }, enabled = !busy)
                    OutlinedTextField(newActivity, { newActivity = it }, Modifier.fillMaxWidth(), label = { Text("Add activity declaration") }, enabled = !busy)
                    OutlinedTextField(updateActivityName, { updateActivityName = it }, Modifier.fillMaxWidth(), label = { Text("Existing activity name to update") }, enabled = !busy)
                    OutlinedTextField(updateActivityExported, { updateActivityExported = it }, Modifier.fillMaxWidth(), label = { Text("exported: true, false, or blank") }, enabled = !busy)
                    OutlinedTextField(updateActivityEnabled, { updateActivityEnabled = it }, Modifier.fillMaxWidth(), label = { Text("enabled: true, false, or blank") }, enabled = !busy)
                    Button(enabled = !busy && updateActivityName.isNotBlank() && isOptionalBoolean(updateActivityExported) && isOptionalBoolean(updateActivityEnabled), onClick = { val currentDraft = current; scope.launch {
                        busy = true
                        val edit = ManifestEdit.UpdateActivity(ActivityInfo(updateActivityName, optionalBoolean(updateActivityExported), optionalBoolean(updateActivityEnabled)))
                        when (val result = withContext(Dispatchers.IO) { drafts.edit(currentDraft.projectId, edit) }) {
                            is DraftEditResult.Saved -> { draft = result.draft; draftValidation = result.validation.issues.joinToString("\n") { it.message }; status = "Activity update saved to draft. original.apk is unchanged." }
                            is DraftEditResult.Rejected -> { draftValidation = result.validation.issues.joinToString("\n") { it.message }; status = "Activity update was rejected." }
                            is DraftEditResult.Unavailable -> status = "Draft is unavailable."
                        }; busy = false
                    } }) { Text("Save activity update") }
                    OutlinedTextField(newPermission, { newPermission = it }, Modifier.fillMaxWidth(), label = { Text("Add uses-permission") }, enabled = !busy)
                    OutlinedTextField(removalName, { removalName = it }, Modifier.fillMaxWidth(), label = { Text("Activity or permission name to remove") }, enabled = !busy)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = !busy && removalName.isNotBlank(), onClick = { removalIsActivity = true; removalConfirmation = true }) { Text("Remove activity...") }
                        Button(enabled = !busy && removalName.isNotBlank(), onClick = { removalIsActivity = false; removalConfirmation = true }) { Text("Remove permission...") }
                    }
                    Button(enabled = !busy, onClick = { scope.launch {
                        busy = true
                        val edits = buildList {
                            add(ManifestEdit.SetLabel(draftLabel.takeIf(String::isNotBlank)?.let { if (it.startsWith("@")) ManifestLabel.ResourceReference(it) else ManifestLabel.Literal(it) }))
                            add(ManifestEdit.SetVersionName(draftVersionName.ifBlank { null }))
                            draftVersionCode.toLongOrNull()?.let { add(ManifestEdit.SetVersionCode(it)) }
                            if (draftMinSdk.toIntOrNull() != null || draftTargetSdk.toIntOrNull() != null) add(ManifestEdit.SetSdk(draftMinSdk.toIntOrNull(), draftTargetSdk.toIntOrNull()))
                            newActivity.takeIf(String::isNotBlank)?.let { add(ManifestEdit.AddActivity(ActivityInfo(it, null, null))) }
                            newPermission.takeIf(String::isNotBlank)?.let { add(ManifestEdit.AddPermission(PermissionInfo(it))) }
                        }
                        var latest = current
                        edits.forEach { edit -> when (val result = withContext(Dispatchers.IO) { drafts.edit(latest.projectId, edit) }) {
                            is DraftEditResult.Saved -> { latest = result.draft; draftValidation = result.validation.issues.joinToString("\n") { it.message } }
                            is DraftEditResult.Rejected -> { draftValidation = result.validation.issues.joinToString("\n") { it.message }; status = "Draft was not saved; fix validation errors." }
                            is DraftEditResult.Unavailable -> status = "Draft is unavailable."
                        } }
                        draft = latest; newActivity = ""; newPermission = ""; if (draftValidation.isBlank()) status = "Draft changes saved under work/. original.apk is unchanged."; busy = false
                    } }) { Text("Save draft changes") }
                    if (draftValidation.isNotBlank()) InspectorSection("Draft validation", draftValidation)
                }
                if (draft != null) Button(enabled = !busy, onClick = { discardConfirmation = true }) { Text("Discard draft...") }
                if (summary != null) SummaryPanel(summary!!) else Text("No in-memory inspection result yet.")
            }
        }
    }
    if (discardConfirmation && draft != null) AlertDialog(
        onDismissRequest = { discardConfirmation = false },
        title = { Text("Discard staged draft?") },
        text = { Text("Only work/manifest-draft.json and its metadata will be deleted. original.apk is unchanged.") },
        confirmButton = { Button(onClick = { val id = draft!!.projectId; scope.launch { busy = true; discardConfirmation = false; when (withContext(Dispatchers.IO) { drafts.discard(id) }) { DraftDiscardResult.Discarded -> { draft = null; status = "Draft discarded. original.apk is unchanged." }; is DraftDiscardResult.Unavailable -> status = "Draft could not be discarded." }; busy = false } }) { Text("Discard draft") } },
        dismissButton = { Button(onClick = { discardConfirmation = false }) { Text("Cancel") } },
    )
    if (removalConfirmation && draft != null) AlertDialog(
        onDismissRequest = { removalConfirmation = false },
        title = { Text(if (removalIsActivity) "Remove activity declaration?" else "Remove uses-permission?") },
        text = { Text("This changes only the staged manifest draft. original.apk is unchanged.") },
        confirmButton = { Button(onClick = { val current = draft!!; val edit = if (removalIsActivity) ManifestEdit.RemoveActivity(removalName) else ManifestEdit.RemovePermission(removalName); scope.launch { busy = true; removalConfirmation = false; when (val result = withContext(Dispatchers.IO) { drafts.edit(current.projectId, edit) }) { is DraftEditResult.Saved -> { draft = result.draft; draftValidation = result.validation.issues.joinToString("\n") { it.message }; removalName = ""; status = "Draft removal saved. original.apk is unchanged." }; is DraftEditResult.Rejected -> { draftValidation = result.validation.issues.joinToString("\n") { it.message }; status = "Draft removal was rejected." }; is DraftEditResult.Unavailable -> status = "Draft is unavailable." }; busy = false } }) { Text("Remove from draft") } },
        dismissButton = { Button(onClick = { removalConfirmation = false }) { Text("Cancel") } },
    )
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

private fun optionalBoolean(value: String): Boolean? = when (value.trim().lowercase()) {
    "" -> null
    "true" -> true
    "false" -> false
    else -> null
}

private fun isOptionalBoolean(value: String): Boolean = value.trim().lowercase() in setOf("", "true", "false")
