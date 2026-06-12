package agents_engine.core

/**
 * #2887 — declared subprocess-execution stance for a tool. Mirrors
 * [ToolNetworkPolicy]'s shape: `Unspecified` (legacy default, declares
 * nothing), `Deny` (explicitly no subprocesses), `Allow` (the executor
 * may spawn subprocesses — `processTool` is the sandboxed way to do so).
 * Consumed by the static body⊆policy comparator
 * (`ToolPolicyCapabilityComparator` in `agents-kt-detekt`) and the
 * manifest verifier's widening gate.
 */
sealed interface ToolExecPolicy {
    val mode: String
    val declaresAnyCapability: Boolean

    fun toManifestMap(): Map<String, Any?> = linkedMapOf("mode" to mode)

    data object Unspecified : ToolExecPolicy {
        override val mode: String = "unspecified"
        override val declaresAnyCapability: Boolean = false
    }

    data object Deny : ToolExecPolicy {
        override val mode: String = "deny"
        override val declaresAnyCapability: Boolean = false
    }

    data object Allow : ToolExecPolicy {
        override val mode: String = "allow"
        override val declaresAnyCapability: Boolean = true
    }

    companion object {
        fun fromManifestMap(map: Map<*, *>): ToolExecPolicy =
            when (map["mode"]?.toString()) {
                "deny" -> Deny
                "allow" -> Allow
                else -> Unspecified
            }
    }
}
