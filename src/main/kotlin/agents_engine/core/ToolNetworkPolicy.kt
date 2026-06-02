package agents_engine.core

sealed interface ToolNetworkPolicy {
    val mode: String
    val hosts: List<String>
    val declaresAnyCapability: Boolean

    fun toManifestMap(): Map<String, Any?> =
        linkedMapOf("mode" to mode, "hosts" to hosts)

    data object Unspecified : ToolNetworkPolicy {
        override val mode: String = "unspecified"
        override val hosts: List<String> = emptyList()
        override val declaresAnyCapability: Boolean = false
    }

    data object DenyAll : ToolNetworkPolicy {
        override val mode: String = "denyAll"
        override val hosts: List<String> = emptyList()
        override val declaresAnyCapability: Boolean = false
    }

    data object AllowAll : ToolNetworkPolicy {
        override val mode: String = "allowAll"
        override val hosts: List<String> = emptyList()
        override val declaresAnyCapability: Boolean = true
    }

    data class Hosts(override val hosts: List<String>) : ToolNetworkPolicy {
        override val mode: String = "hosts"
        override val declaresAnyCapability: Boolean = hosts.isNotEmpty()
    }

    companion object {
        fun fromManifestMap(map: Map<*, *>): ToolNetworkPolicy =
            when (map["mode"]?.toString()) {
                "denyAll" -> DenyAll
                "allowAll" -> AllowAll
                "hosts" -> Hosts(ManifestMaps.stringList(map["hosts"]))
                else -> Unspecified
            }
    }
}
