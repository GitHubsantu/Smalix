package com.imdevops.smalix.core.model

data class ResourceIndex(
    val entries: List<ResourceEntry>,
) {
    init { require(entries.map { it.resourceId to it.configuration }.distinct().size == entries.size) }
}

data class ResourceEntry(
    val resourceId: Long,
    val packageName: String,
    val type: ResourceType,
    val name: String,
    val configuration: String,
    val value: ResourceValue,
    val sourceEntryPath: String,
) {
    init {
        require(resourceId in 0..0xffff_ffffL)
        require(PACKAGE.matches(packageName))
        require(NAME.matches(name))
        require(configuration.isEmpty() || QUALIFIER.matches(configuration))
        require(sourceEntryPath == "resources.arsc" || sourceEntryPath.startsWith("res/"))
    }

    val qualifiedName: String get() = "$packageName:${type.wireName}/$name"

    private companion object {
        val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_.]*$")
        val NAME = Regex("^[A-Za-z_][A-Za-z0-9_]*$")
        val QUALIFIER = Regex("^[A-Za-z0-9_-]+$")
    }
}

enum class ResourceType(val wireName: String) { STRING("string"), COLOR("color"), DIMEN("dimen") }

sealed interface ResourceValue {
    data class Literal(val value: String) : ResourceValue
    data class Reference(val value: String) : ResourceValue {
        init { require(value.startsWith("@") || value.startsWith("?")) }
    }
}

sealed interface ResourceQuery {
    data class ExactName(val packageName: String, val type: ResourceType, val name: String) : ResourceQuery
    data class Text(val value: String) : ResourceQuery { init { require(value.isNotBlank()) } }
}
