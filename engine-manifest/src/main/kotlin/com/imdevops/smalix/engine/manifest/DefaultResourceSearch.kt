package com.imdevops.smalix.engine.manifest

import com.imdevops.smalix.core.model.ResourceEntry
import com.imdevops.smalix.core.model.ResourceIndex
import com.imdevops.smalix.core.model.ResourceQuery
import com.imdevops.smalix.core.model.ResourceValue
import com.imdevops.smalix.engine.api.ResourceSearch

/** Pure in-memory resource search; it does not read, extract, or change an APK. */
class DefaultResourceSearch : ResourceSearch {
    override fun find(index: ResourceIndex, query: ResourceQuery): List<ResourceEntry> = when (query) {
        is ResourceQuery.ExactName -> index.entries.filter {
            it.packageName == query.packageName && it.type == query.type && it.name == query.name
        }
        is ResourceQuery.Text -> index.entries.filter {
            it.qualifiedName.contains(query.value, ignoreCase = true) ||
                (it.value as? ResourceValue.Literal)?.value?.contains(query.value, ignoreCase = true) == true
        }
    }
}
