package agents_engine.manifest

class PermissionManifestOptions {
    var includeProviderConfig: Boolean = true
    var includeBudgets: Boolean = true
    var includeMcp: Boolean = true
    var includeMemory: Boolean = true
    var includePolicy: Boolean = true
    var includeComposition: Boolean = true

    internal fun toMap(): Map<String, Any?> = sortedMapOf(
        "includeBudgets" to includeBudgets,
        "includeComposition" to includeComposition,
        "includeMcp" to includeMcp,
        "includeMemory" to includeMemory,
        "includePolicy" to includePolicy,
        "includeProviderConfig" to includeProviderConfig,
    )
}
