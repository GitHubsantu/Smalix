package com.imdevops.smalix.core.model

data class DexIndex(
    val dexEntries: List<DexEntry>,
    val classes: List<DexClass>,
) {
    init {
        require(dexEntries.map(DexEntry::name).distinct().size == dexEntries.size)
        require(classes.map(DexClass::descriptor).distinct().size == classes.size)
    }
}

data class DexEntry(val name: String, val classCount: Int) {
    init {
        require(DEX_NAME.matches(name)) { "DEX entry name is invalid." }
        require(classCount >= 0)
    }

    private companion object { val DEX_NAME = Regex("^classes(?:[2-9]|[1-9][0-9]+)?\\.dex$") }
}

data class DexClass(
    val dexEntryName: String,
    val descriptor: String,
    val accessFlags: Int,
    val fields: List<DexField>,
    val methods: List<DexMethod>,
) {
    init {
        require(descriptor.matches(Regex("^L[^;]+;$"))) { "Class descriptor is invalid." }
        require(fields.map(DexField::name).distinct().size == fields.size)
        require(methods.map { it.name + it.descriptor }.distinct().size == methods.size)
    }

    val packageName: String get() = descriptor.removePrefix("L").removeSuffix(";").substringBeforeLast('/', "").replace('/', '.')
}

data class DexField(val name: String, val typeDescriptor: String, val accessFlags: Int)
data class DexMethod(val name: String, val descriptor: String, val accessFlags: Int, val codeOffset: Long?) {
    init { require(codeOffset == null || codeOffset >= 0) }
}

sealed interface DexClassQuery {
    data class ExactDescriptor(val descriptor: String) : DexClassQuery
    data class PackagePrefix(val packageName: String) : DexClassQuery
}
