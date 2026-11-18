package com.imdevops.smalix.engine.manifest

import com.imdevops.smalix.core.model.ManifestDraft
import com.imdevops.smalix.core.model.ManifestInfo
import com.imdevops.smalix.core.model.ManifestLabel
import com.imdevops.smalix.core.model.PermissionInfo
import com.imdevops.smalix.core.model.ProjectId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ManifestDraftApplicationPlannerTest {
    private val planner = ManifestDraftApplicationPlanner()

    @Test fun `plans a literal android label replacement without changing bytes`() {
        val original = manifest(ManifestLabel.Literal("Old"))
        val draft = draft(original).copy(label = ManifestLabel.Literal("New"))

        val result = planner.plan(document("Old"), original, draft)

        assertEquals(
            ManifestDraftApplicationPlan.Ready(
                listOf(ManifestStringReplacement(listOf(0, 0), 0, 4, "Old", "New")),
            ),
            result,
        )
    }

    @Test fun `rejects structural permission changes until a lossless writer supports them`() {
        val original = manifest(ManifestLabel.Literal("Old"))
        val draft = draft(original).copy(permissions = listOf(PermissionInfo("android.permission.INTERNET")))

        val result = planner.plan(document("Old"), original, draft)

        val rejected = assertInstanceOf(ManifestDraftApplicationPlan.Rejected::class.java, result)
        assertTrue(rejected.message.contains("literal android:label"))
    }

    @Test fun `rejects a resource backed label as a literal replacement target`() {
        val original = manifest(ManifestLabel.ResourceReference("@string/app_name"))
        val draft = draft(original).copy(label = ManifestLabel.Literal("New"))

        val result = planner.plan(document("@string/app_name"), original, draft)

        val rejected = assertInstanceOf(ManifestDraftApplicationPlan.Rejected::class.java, result)
        assertTrue(rejected.message.contains("resource-backed"))
    }

    @Test fun `rejects a literal label string shared by another manifest field`() {
        val original = manifest(ManifestLabel.Literal("Old"))
        val draft = draft(original).copy(label = ManifestLabel.Literal("New"))

        val result = planner.plan(document("Old", shared = true), original, draft)

        val rejected = assertInstanceOf(ManifestDraftApplicationPlan.Rejected::class.java, result)
        assertTrue(rejected.message.contains("shared"))
    }

    private fun manifest(label: ManifestLabel) = ManifestInfo(
        packageName = "com.example",
        versionName = "1.0",
        versionCode = 1,
        minSdk = 26,
        targetSdk = 35,
        label = label,
        activities = emptyList(),
        permissions = emptyList(),
    )

    private fun draft(manifest: ManifestInfo) = ManifestDraft.from(ProjectId.random(), "a".repeat(64), manifest)

    private fun document(label: String, shared: Boolean = false): BinaryXmlDocument = BinaryXmlDocument(
        bytes = byteArrayOf(),
        chunks = emptyList(),
        strings = listOf("manifest", "application", ANDROID_NAMESPACE, "label", label, "meta-data"),
        roots = listOf(
            BinaryXmlElement(
                namespaceIndex = -1,
                nameIndex = 0,
                attributes = emptyList(),
                children = listOf(
                    BinaryXmlElement(
                        namespaceIndex = -1,
                        nameIndex = 1,
                        attributes = listOf(BinaryXmlAttribute(2, 3, 4, 0x03, 4)),
                        children = emptyList(),
                    ),
                    *if (shared) arrayOf(
                        BinaryXmlElement(
                            namespaceIndex = -1,
                            nameIndex = 5,
                            attributes = listOf(BinaryXmlAttribute(2, 3, 4, 0x03, 4)),
                            children = emptyList(),
                        ),
                    ) else emptyArray(),
                ),
            ),
        ),
    )

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    }
}
