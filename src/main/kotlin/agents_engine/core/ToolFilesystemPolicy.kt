package agents_engine.core

data class ToolFilesystemPolicy(
    val read: ToolFilesystemAccess = ToolFilesystemAccess.Unspecified,
    val write: ToolFilesystemAccess = ToolFilesystemAccess.Unspecified,
) {
    val declaresAnyCapability: Boolean
        get() = read.declaresCapability || write.declaresCapability

    fun toManifestMap(): Map<String, Any?> =
        linkedMapOf(
            "read" to read.toManifestMap(),
            "write" to write.toManifestMap(),
        )

    companion object {
        fun fromManifestMap(map: Map<*, *>): ToolFilesystemPolicy =
            ToolFilesystemPolicy(
                read = ToolFilesystemAccess.fromManifestMap(ManifestMaps.map(map["read"])),
                write = ToolFilesystemAccess.fromManifestMap(ManifestMaps.map(map["write"])),
            )
    }
}
