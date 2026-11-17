package com.imdevops.smalix.engine.manifest

import com.imdevops.smalix.core.model.ActivityInfo
import com.imdevops.smalix.core.model.ManifestDraft
import com.imdevops.smalix.core.model.ManifestEdit
import com.imdevops.smalix.core.model.ManifestEditResult
import com.imdevops.smalix.core.model.ManifestIssue
import com.imdevops.smalix.core.model.ManifestIssueSeverity
import com.imdevops.smalix.core.model.ManifestLabel
import com.imdevops.smalix.core.model.ManifestValidation
import com.imdevops.smalix.core.model.PermissionInfo
import com.imdevops.smalix.engine.api.ManifestDraftEditor
import com.imdevops.smalix.engine.api.ManifestDraftValidator

class DefaultManifestDraftEditor(
    private val validator: ManifestDraftValidator = DefaultManifestDraftValidator(),
) : ManifestDraftEditor {
    override fun apply(draft: ManifestDraft, edit: ManifestEdit): ManifestEditResult {
        val operationIssues = mutableListOf<ManifestIssue>()
        val next = when (edit) {
            is ManifestEdit.SetLabel -> draft.next(label = edit.label)
            is ManifestEdit.SetVersionName -> draft.next(versionName = edit.value?.trim())
            is ManifestEdit.SetVersionCode -> draft.next(versionCode = edit.value)
            is ManifestEdit.SetSdk -> draft.next(minSdk = edit.minSdk, targetSdk = edit.targetSdk)
            is ManifestEdit.AddActivity -> draft.next(activities = draft.activities + edit.activity.normalized())
            is ManifestEdit.UpdateActivity -> draft.replaceActivity(edit.activity, operationIssues)
            is ManifestEdit.RemoveActivity -> draft.removeActivity(edit.name, operationIssues)
            is ManifestEdit.AddPermission -> draft.next(permissions = draft.permissions + PermissionInfo(edit.permission.name.trim()))
            is ManifestEdit.RemovePermission -> draft.removePermission(edit.name, operationIssues)
        }
        return ManifestEditResult(next, ManifestValidation(operationIssues + validator.validate(next).issues))
    }

    private fun ManifestDraft.replaceActivity(
        activity: ActivityInfo,
        issues: MutableList<ManifestIssue>,
    ): ManifestDraft {
        val name = activity.name.trim()
        val index = activities.indexOfFirst { it.name.trim() == name }
        if (index < 0) return missing("activities", name, issues)
        return next(activities = activities.toMutableList().also { it[index] = activity.normalized() })
    }

    private fun ManifestDraft.removeActivity(name: String, issues: MutableList<ManifestIssue>): ManifestDraft {
        val normalized = name.trim()
        if (activities.none { it.name.trim() == normalized }) return missing("activities", normalized, issues)
        return next(activities = activities.filterNot { it.name.trim() == normalized })
    }

    private fun ManifestDraft.removePermission(name: String, issues: MutableList<ManifestIssue>): ManifestDraft {
        val normalized = name.trim()
        if (permissions.none { it.name.trim() == normalized }) return missing("permissions", normalized, issues)
        return next(permissions = permissions.filterNot { it.name.trim() == normalized })
    }

    private fun ManifestDraft.missing(field: String, name: String, issues: MutableList<ManifestIssue>): ManifestDraft {
        issues += ManifestIssue(ManifestIssueSeverity.ERROR, field, "No $field entry named '$name' exists.")
        return this
    }

    private fun ManifestDraft.next(
        versionName: String? = this.versionName,
        versionCode: Long? = this.versionCode,
        minSdk: Int? = this.minSdk,
        targetSdk: Int? = this.targetSdk,
        label: ManifestLabel? = this.label,
        activities: List<ActivityInfo> = this.activities,
        permissions: List<PermissionInfo> = this.permissions,
    ) = copy(
        revision = revision + 1,
        versionName = versionName,
        versionCode = versionCode,
        minSdk = minSdk,
        targetSdk = targetSdk,
        label = label,
        activities = activities,
        permissions = permissions,
    )

    private fun ActivityInfo.normalized() = copy(name = name.trim())
}

class DefaultManifestDraftValidator : ManifestDraftValidator {
    override fun validate(draft: ManifestDraft): ManifestValidation {
        val issues = mutableListOf<ManifestIssue>()
        val versionName = draft.versionName
        val versionCode = draft.versionCode
        val minSdk = draft.minSdk
        val targetSdk = draft.targetSdk
        if (versionName != null && versionName.isBlank()) error(issues, "versionName", "Version name must not be blank.")
        if (versionCode != null && versionCode <= 0) error(issues, "versionCode", "Version code must be positive.")
        if (minSdk != null && minSdk < 0) error(issues, "minSdk", "Minimum SDK must not be negative.")
        if (targetSdk != null && targetSdk < 0) error(issues, "targetSdk", "Target SDK must not be negative.")
        if (minSdk != null && targetSdk != null && minSdk > targetSdk) {
            error(issues, "sdk", "Minimum SDK must not exceed target SDK.")
        }
        validateLabel(draft.label, issues)
        validateActivities(draft, issues)
        validatePermissions(draft.permissions, issues)
        return ManifestValidation(issues)
    }

    private fun validateLabel(label: ManifestLabel?, issues: MutableList<ManifestIssue>) {
        when (label) {
            null -> Unit
            is ManifestLabel.Literal -> if (label.value.isBlank()) error(issues, "label", "Literal app label must not be blank.")
            is ManifestLabel.ResourceReference -> if (!RESOURCE_REFERENCE.matches(label.value)) {
                error(issues, "label", "App label resource reference is invalid.")
            }
        }
    }

    private fun validateActivities(draft: ManifestDraft, issues: MutableList<ManifestIssue>) {
        val names = draft.activities.map { it.name.trim() }
        names.forEach { name -> if (!COMPONENT_NAME.matches(name)) error(issues, "activities", "Activity name '$name' is invalid.") }
        duplicates(names).forEach { error(issues, "activities", "Duplicate activity '$it'.") }
        val original = draft.originalActivityNames.map(String::trim).toSet()
        names.filterNot(original::contains).forEach { warning(issues, "activities", "Activity '$it' has no verified DEX implementation.") }
    }

    private fun validatePermissions(permissions: List<PermissionInfo>, issues: MutableList<ManifestIssue>) {
        val names = permissions.map { it.name.trim() }
        names.forEach { name -> if (!COMPONENT_NAME.matches(name)) error(issues, "permissions", "Permission name '$name' is invalid.") }
        duplicates(names).forEach { error(issues, "permissions", "Duplicate permission '$it'.") }
    }

    private fun duplicates(names: List<String>) = names.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
    private fun error(issues: MutableList<ManifestIssue>, field: String, message: String) = issues.add(ManifestIssue(ManifestIssueSeverity.ERROR, field, message))
    private fun warning(issues: MutableList<ManifestIssue>, field: String, message: String) = issues.add(ManifestIssue(ManifestIssueSeverity.WARNING, field, message))

    private companion object {
        val COMPONENT_NAME = Regex("^[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_$]*)+$")
        val RESOURCE_REFERENCE = Regex("^@[A-Za-z0-9_./:]+$")
    }
}
