package com.imdevops.smalix.engine.dex

import com.imdevops.smalix.core.model.DexClass
import com.imdevops.smalix.core.model.DexClassQuery
import com.imdevops.smalix.core.model.DexIndex
import com.imdevops.smalix.engine.api.DexSearch

/** Searches an already bounded, in-memory DEX index; it never opens or changes an APK. */
class DefaultDexSearch : DexSearch {
    override fun find(index: DexIndex, query: DexClassQuery): List<DexClass> = when (query) {
        is DexClassQuery.ExactDescriptor -> index.classes.filter { it.descriptor == query.descriptor }
        is DexClassQuery.PackagePrefix -> {
            val prefix = query.packageName.trim().trimEnd('.')
            index.classes.filter { it.packageName == prefix || it.packageName.startsWith("$prefix.") }
        }
    }
}
