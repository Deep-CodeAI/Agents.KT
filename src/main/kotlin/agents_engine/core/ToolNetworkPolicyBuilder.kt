package agents_engine.core

import java.util.logging.Logger

class ToolNetworkPolicyBuilder(initial: ToolNetworkPolicy = ToolNetworkPolicy.Unspecified) {
    private val hosts = linkedSetOf<String>()
    private var mode: Mode = Mode.UNSPECIFIED

    init {
        when (initial) {
            is ToolNetworkPolicy.Hosts -> {
                mode = Mode.HOSTS
                hosts += initial.hosts
            }
            ToolNetworkPolicy.AllowAll -> mode = Mode.ALLOW_ALL
            ToolNetworkPolicy.DenyAll -> mode = Mode.DENY_ALL
            ToolNetworkPolicy.Unspecified -> Unit
        }
    }

    fun allow(host: String) {
        mode = Mode.HOSTS
        hosts += nonBlank(host, "network host")
    }

    fun denyAll() {
        mode = Mode.DENY_ALL
        hosts.clear()
    }

    fun allowAll() {
        LOGGER.warning(
            "Tool policy declares network.allowAll(); this is declarative only in 0.6.0 " +
                "and should be treated as high-risk in manifest review.",
        )
        mode = Mode.ALLOW_ALL
        hosts.clear()
    }

    fun build(): ToolNetworkPolicy =
        when (mode) {
            Mode.UNSPECIFIED -> ToolNetworkPolicy.Unspecified
            Mode.DENY_ALL -> ToolNetworkPolicy.DenyAll
            Mode.ALLOW_ALL -> ToolNetworkPolicy.AllowAll
            Mode.HOSTS -> ToolNetworkPolicy.Hosts(hosts.toList())
        }

    private enum class Mode { UNSPECIFIED, DENY_ALL, ALLOW_ALL, HOSTS }

    private companion object {
        val LOGGER: Logger = Logger.getLogger(ToolPolicy::class.java.name)
    }
}
