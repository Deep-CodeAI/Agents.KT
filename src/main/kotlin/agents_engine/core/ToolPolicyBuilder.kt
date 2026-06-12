package agents_engine.core

class ToolPolicyBuilder {
    var risk: ToolRisk = ToolRisk.LOW
    private var filesystem: ToolFilesystemPolicy = ToolFilesystemPolicy()
    private var network: ToolNetworkPolicy = ToolNetworkPolicy.Unspecified
    private var environment: ToolEnvironmentPolicy = ToolEnvironmentPolicy.Unspecified
    private var exec: ToolExecPolicy = ToolExecPolicy.Unspecified

    fun filesystem(block: ToolFilesystemPolicyBuilder.() -> Unit) {
        filesystem = ToolFilesystemPolicyBuilder(filesystem).apply(block).build()
    }

    fun network(block: ToolNetworkPolicyBuilder.() -> Unit) {
        network = ToolNetworkPolicyBuilder(network).apply(block).build()
    }

    fun environment(block: ToolEnvironmentPolicyBuilder.() -> Unit) {
        environment = ToolEnvironmentPolicyBuilder(environment).apply(block).build()
    }

    /** #2887 — declared subprocess stance: `exec { allow() }` / `exec { deny() }`. */
    fun exec(block: ToolExecPolicyBuilder.() -> Unit) {
        exec = ToolExecPolicyBuilder(exec).apply(block).build()
    }

    fun build(): ToolPolicy =
        ToolPolicy(
            risk = risk,
            filesystem = filesystem,
            network = network,
            environment = environment,
            exec = exec,
        )
}
