package com.imdevops.smalix.engine.manifest

import com.imdevops.smalix.core.model.ActivityInfo
import com.imdevops.smalix.core.model.ManifestDraft
import com.imdevops.smalix.core.model.ManifestEdit
import com.imdevops.smalix.core.model.ManifestInfo
import com.imdevops.smalix.core.model.ManifestIssueSeverity
import com.imdevops.smalix.core.model.ManifestLabel
import com.imdevops.smalix.core.model.PermissionInfo
import com.imdevops.smalix.core.model.ProjectId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ManifestDraftEditorTest {
    private val editor = DefaultManifestDraftEditor()

    @Test
    fun `adds activity to a new draft and warns that DEX is unverified`() {
        val original = draft()

        val result = editor.apply(original, ManifestEdit.AddActivity(ActivityInfo("com.example.SecondActivity", true, null)))

        assertEquals(0, original.revision)
        assertEquals(listOf("com.example.MainActivity"), original.activities.map { it.name })
        assertEquals(1, result.draft.revision)
        assertEquals(listOf("com.example.MainActivity", "com.example.SecondActivity"), result.draft.activities.map { it.name })
        assertTrue(result.validation.isValid)
        assertTrue(result.validation.issues.any { it.severity == ManifestIssueSeverity.WARNING && it.message.contains("DEX") })
    }

    @Test
    fun `rejects invalid scalar values and duplicate permissions`() {
        var current = draft()
        current = editor.apply(current, ManifestEdit.SetVersionCode(0)).draft
        current = editor.apply(current, ManifestEdit.SetSdk(35, 26)).draft
        current = editor.apply(current, ManifestEdit.AddPermission(PermissionInfo("android.permission.INTERNET"))).draft

        val validation = DefaultManifestDraftValidator().validate(current)

        assertFalse(validation.isValid)
        assertTrue(validation.issues.any { it.field == "versionCode" && it.severity == ManifestIssueSeverity.ERROR })
        assertTrue(validation.issues.any { it.field == "sdk" && it.severity == ManifestIssueSeverity.ERROR })
        assertTrue(validation.issues.any { it.field == "permissions" && it.message.contains("Duplicate") })
    }

    @Test
    fun `preserves raw resource labels and reports an unknown removal`() {
        val withReference = editor.apply(draft(), ManifestEdit.SetLabel(ManifestLabel.ResourceReference("@string/app_name")))
        val missingRemoval = editor.apply(withReference.draft, ManifestEdit.RemoveActivity("com.example.MissingActivity"))

        assertEquals(ManifestLabel.ResourceReference("@string/app_name"), withReference.draft.label)
        assertTrue(withReference.validation.isValid)
        assertEquals(withReference.draft.revision, missingRemoval.draft.revision)
        assertTrue(missingRemoval.validation.issues.any { it.message.contains("No activities entry") })
    }

    private fun draft() = ManifestDraft.from(
        projectId = ProjectId.random(),
        originalApkSha256 = "a".repeat(64),
        manifest = ManifestInfo(
            packageName = "com.example",
            versionName = "1.0",
            versionCode = 1,
            minSdk = 26,
            targetSdk = 35,
            label = ManifestLabel.Literal("Example"),
            activities = listOf(ActivityInfo("com.example.MainActivity", true, null)),
            permissions = listOf(PermissionInfo("android.permission.INTERNET")),
        ),
    )
}
