package com.imdevops.smalix.engine.manifest

import com.imdevops.smalix.core.model.ManifestDraft
import com.imdevops.smalix.core.model.ManifestInfo
import com.imdevops.smalix.core.model.ManifestIssueSeverity
import com.imdevops.smalix.core.model.ManifestLabel
import com.imdevops.smalix.engine.api.ManifestDraftValidator

/**
 * Builds a bounded, in-memory application plan only. It never changes the source document or APK bytes.
 *
 * The first supported target is an existing literal `android:label`; every other changed field is rejected
 * until the lossless binary-AXML writer can represent it without dropping document information.
 */
class ManifestDraftApplicationPlanner(
    private val validator: ManifestDraftValidator = DefaultManifestDraftValidator(),
) {
    fun plan(document: BinaryXmlDocument, original: ManifestInfo, draft: ManifestDraft): ManifestDraftApplicationPlan {
        if (original.packageName != draft.packageName) return ManifestDraftApplicationPlan.Rejected("Draft package does not match the inspected manifest.")
        val validation = validator.validate(draft)
        if (!validation.isValid) return ManifestDraftApplicationPlan.Rejected(validation.issues.first { it.severity == ManifestIssueSeverity.ERROR }.message)
        if (hasUnsupportedChange(original, draft)) {
            return ManifestDraftApplicationPlan.Rejected("This slice supports only an existing literal android:label change.")
        }
        if (original.label == draft.label) return ManifestDraftApplicationPlan.Ready(emptyList())

        val oldLabel = original.label as? ManifestLabel.Literal
            ?: return ManifestDraftApplicationPlan.Rejected("A resource-backed or missing app label cannot be replaced as a literal.")
        val newLabel = draft.label as? ManifestLabel.Literal
            ?: return ManifestDraftApplicationPlan.Rejected("App label replacement must be a literal value.")
        val applications = document.descendants("application")
        if (applications.size != 1) return ManifestDraftApplicationPlan.Rejected("Expected exactly one application element, found ${applications.size}.")
        val application = applications.single()
        val labels = application.element.attributes.withIndex().filter { (_, attribute) ->
            attribute.namespaceIndex == application.androidNamespaceIndex && application.strings[attribute.nameIndex] == "label"
        }
        if (labels.size != 1) return ManifestDraftApplicationPlan.Rejected("Expected exactly one android:label attribute.")
        val (attributeIndex, attribute) = labels.single()
        if (attribute.rawValueIndex !in application.strings.indices || application.strings[attribute.rawValueIndex] != oldLabel.value) {
            return ManifestDraftApplicationPlan.Rejected("The inspected app label does not match the draft source value.")
        }
        if (attribute.valueType != TYPE_STRING) return ManifestDraftApplicationPlan.Rejected("App label is not a literal string value.")

        return ManifestDraftApplicationPlan.Ready(
            listOf(
                ManifestStringReplacement(
                    elementPath = application.path,
                    attributeIndex = attributeIndex,
                    stringPoolIndex = attribute.rawValueIndex,
                    originalValue = oldLabel.value,
                    replacementValue = newLabel.value,
                ),
            ),
        )
    }

    private fun hasUnsupportedChange(original: ManifestInfo, draft: ManifestDraft): Boolean =
        original.versionName != draft.versionName || original.versionCode != draft.versionCode ||
            original.minSdk != draft.minSdk || original.targetSdk != draft.targetSdk ||
            original.activities != draft.activities || original.permissions != draft.permissions

    private data class ElementMatch(
        val element: BinaryXmlElement,
        val path: List<Int>,
        val strings: List<String>,
        val androidNamespaceIndex: Int,
    )

    private fun BinaryXmlDocument.descendants(name: String): List<ElementMatch> = buildList {
        fun visit(element: BinaryXmlElement, path: List<Int>) {
            if (strings[element.nameIndex] == name) {
                val androidNamespaceIndex = strings.indexOf(ANDROID_NAMESPACE)
                add(ElementMatch(element, path, strings, androidNamespaceIndex))
            }
            element.children.forEachIndexed { index, child -> visit(child, path + index) }
        }
        rootElements.forEachIndexed { index, element -> visit(element, listOf(index)) }
    }

    private companion object {
        const val TYPE_STRING = 0x03
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    }
}

sealed interface ManifestDraftApplicationPlan {
    data class Ready(val stringReplacements: List<ManifestStringReplacement>) : ManifestDraftApplicationPlan
    data class Rejected(val message: String) : ManifestDraftApplicationPlan
}

data class ManifestStringReplacement(
    val elementPath: List<Int>,
    val attributeIndex: Int,
    val stringPoolIndex: Int,
    val originalValue: String,
    val replacementValue: String,
) {
    init {
        require(elementPath.isNotEmpty() && elementPath.all { it >= 0 })
        require(attributeIndex >= 0 && stringPoolIndex >= 0)
    }
}
