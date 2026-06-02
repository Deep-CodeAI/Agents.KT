package agents_engine.core

sealed interface ToolEnvironmentPolicy {
    val mode: String
    val variables: List<String>
    val declaresAnyCapability: Boolean

    fun toManifestMap(): Map<String, Any?> =
        linkedMapOf("mode" to mode, "variables" to variables)

    data object Unspecified : ToolEnvironmentPolicy {
        override val mode: String = "unspecified"
        override val variables: List<String> = emptyList()
        override val declaresAnyCapability: Boolean = false
    }

    data object DenyAll : ToolEnvironmentPolicy {
        override val mode: String = "denyAll"
        override val variables: List<String> = emptyList()
        override val declaresAnyCapability: Boolean = false
    }

    data class Vars(override val variables: List<String>) : ToolEnvironmentPolicy {
        override val mode: String = "vars"
        override val declaresAnyCapability: Boolean = variables.isNotEmpty()
    }

    companion object {
        fun fromManifestMap(map: Map<*, *>): ToolEnvironmentPolicy =
            when (map["mode"]?.toString()) {
                "denyAll" -> DenyAll
                "vars" -> Vars(ManifestMaps.stringList(map["variables"]))
                else -> Unspecified
            }
    }
}
