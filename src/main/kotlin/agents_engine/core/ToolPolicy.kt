package agents_engine.core

import agents_engine.generation.LenientJsonParser

/**
 * Declarative sandbox policy for a tool.
 *
 * Originally an audit/manifest-only declaration (0.6.0). As of #2890 the declared
 * **filesystem** stance is enforced in-JVM at the tool-call boundary (Layer 1): a
 * call whose absolute path arguments fall outside the declared `read`/`write` globs
 * is denied before the executor runs — see [ToolPolicyEnforcer] and
 * `docs/tool-policy-enforcement.md`. OS/container enforcement of the process itself
 * (network/environment, subprocess isolation) is the Layer-2 sandbox, tracked in #1916.
 */
data class ToolPolicy(
    val risk: ToolRisk = ToolRisk.LOW,
    val filesystem: ToolFilesystemPolicy = ToolFilesystemPolicy(),
    val network: ToolNetworkPolicy = ToolNetworkPolicy.Unspecified,
    val environment: ToolEnvironmentPolicy = ToolEnvironmentPolicy.Unspecified,
    /** #2887 — declared subprocess stance; see [ToolExecPolicy]. */
    val exec: ToolExecPolicy = ToolExecPolicy.Unspecified,
) {
    val declaresAnyCapability: Boolean
        get() = filesystem.declaresAnyCapability ||
            network.declaresAnyCapability ||
            environment.declaresAnyCapability ||
            exec.declaresAnyCapability

    fun toManifestMap(): Map<String, Any?> =
        linkedMapOf(
            "risk" to risk.manifestName,
            "filesystem" to filesystem.toManifestMap(),
            "network" to network.toManifestMap(),
            "environment" to environment.toManifestMap(),
            "exec" to exec.toManifestMap(),
        )

    fun toManifestJson(): String = ManifestJson.encode(toManifestMap())

    fun toManifestYaml(): String = buildString {
        appendLine("risk: ${risk.manifestName}")
        appendLine("filesystem:")
        appendFilesystemAccess("read", filesystem.read)
        appendFilesystemAccess("write", filesystem.write)
        appendLine("network:")
        appendSimplePolicy(network.toManifestMap(), listKey = "hosts")
        appendLine("environment:")
        appendSimplePolicy(environment.toManifestMap(), listKey = "variables")
        appendLine("exec:")
        appendLine("  mode: ${'$'}{exec.mode}")
    }.trimEnd()

    private fun StringBuilder.appendFilesystemAccess(name: String, access: ToolFilesystemAccess) {
        appendLine("  $name:")
        appendLine("    mode: ${access.mode}")
        appendLine("    globs:")
        access.globs.forEach { appendLine("      - ${ManifestYaml.quote(it)}") }
    }

    private fun StringBuilder.appendSimplePolicy(map: Map<String, Any?>, listKey: String) {
        appendLine("  mode: ${map["mode"]}")
        appendLine("  $listKey:")
        ManifestMaps.stringList(map[listKey]).forEach {
            appendLine("    - ${ManifestYaml.quote(it)}")
        }
    }

    companion object {
        fun fromManifestMap(map: Map<*, *>): ToolPolicy =
            ToolPolicy(
                risk = ToolRisk.fromManifest(map["risk"]?.toString()),
                filesystem = ToolFilesystemPolicy.fromManifestMap(ManifestMaps.map(map["filesystem"])),
                network = ToolNetworkPolicy.fromManifestMap(ManifestMaps.map(map["network"])),
                environment = ToolEnvironmentPolicy.fromManifestMap(ManifestMaps.map(map["environment"])),
                exec = ToolExecPolicy.fromManifestMap(ManifestMaps.map(map["exec"])),
            )

        fun fromManifestJson(json: String): ToolPolicy {
            val parsed = LenientJsonParser.parse(json) as? Map<*, *>
                ?: error("ToolPolicy manifest JSON must be an object")
            return fromManifestMap(parsed)
        }

        fun fromManifestYaml(yaml: String): ToolPolicy =
            fromManifestMap(ManifestYaml.parsePolicyMap(yaml))
    }
}

fun toolPolicy(block: ToolPolicyBuilder.() -> Unit): ToolPolicy =
    ToolPolicyBuilder().apply(block).build()

internal fun nonBlank(value: String, label: String): String {
    val trimmed = value.trim()
    require(trimmed.isNotEmpty()) { "$label must not be blank" }
    return trimmed
}
