package agents_engine.core

sealed interface ToolFilesystemAccess {
    val mode: String
    val globs: List<String>
    val declaresCapability: Boolean

    fun toManifestMap(): Map<String, Any?> =
        linkedMapOf("mode" to mode, "globs" to globs)

    data object Unspecified : ToolFilesystemAccess {
        override val mode: String = "unspecified"
        override val globs: List<String> = emptyList()
        override val declaresCapability: Boolean = false
    }

    data object None : ToolFilesystemAccess {
        override val mode: String = "none"
        override val globs: List<String> = emptyList()
        override val declaresCapability: Boolean = false
    }

    data class Globs(override val globs: List<String>) : ToolFilesystemAccess {
        override val mode: String = "globs"
        override val declaresCapability: Boolean = globs.isNotEmpty()
    }

    companion object {
        fun fromManifestMap(map: Map<*, *>): ToolFilesystemAccess =
            when (map["mode"]?.toString()) {
                "none" -> None
                "globs" -> Globs(ManifestMaps.stringList(map["globs"]))
                else -> Unspecified
            }
    }
}
