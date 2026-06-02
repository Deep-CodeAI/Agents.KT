package agents_engine.core

class ToolEnvironmentPolicyBuilder(initial: ToolEnvironmentPolicy = ToolEnvironmentPolicy.Unspecified) {
    private val variables = linkedSetOf<String>()
    private var mode: Mode = Mode.UNSPECIFIED

    init {
        when (initial) {
            is ToolEnvironmentPolicy.Vars -> {
                mode = Mode.VARS
                variables += initial.variables
            }
            ToolEnvironmentPolicy.DenyAll -> mode = Mode.DENY_ALL
            ToolEnvironmentPolicy.Unspecified -> Unit
        }
    }

    fun allow(varName: String) {
        mode = Mode.VARS
        variables += nonBlank(varName, "environment variable")
    }

    fun denyAll() {
        mode = Mode.DENY_ALL
        variables.clear()
    }

    fun build(): ToolEnvironmentPolicy =
        when (mode) {
            Mode.UNSPECIFIED -> ToolEnvironmentPolicy.Unspecified
            Mode.DENY_ALL -> ToolEnvironmentPolicy.DenyAll
            Mode.VARS -> ToolEnvironmentPolicy.Vars(variables.toList())
        }

    private enum class Mode { UNSPECIFIED, DENY_ALL, VARS }
}
