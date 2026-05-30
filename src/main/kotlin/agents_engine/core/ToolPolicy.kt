package agents_engine.core

import agents_engine.generation.LenientJsonParser
import java.util.logging.Logger

/**
 * Declarative sandbox policy for a tool.
 *
 * This is an audit/manifest declaration in 0.6.0, not an enforcement layer.
 * The process/container enforcement backend is tracked separately (#1916).
 */
data class ToolPolicy(
    val risk: ToolRisk = ToolRisk.LOW,
    val filesystem: ToolFilesystemPolicy = ToolFilesystemPolicy(),
    val network: ToolNetworkPolicy = ToolNetworkPolicy.Unspecified,
    val environment: ToolEnvironmentPolicy = ToolEnvironmentPolicy.Unspecified,
) {
    val declaresAnyCapability: Boolean
        get() = filesystem.declaresAnyCapability ||
            network.declaresAnyCapability ||
            environment.declaresAnyCapability

    fun toManifestMap(): Map<String, Any?> =
        linkedMapOf(
            "risk" to risk.manifestName,
            "filesystem" to filesystem.toManifestMap(),
            "network" to network.toManifestMap(),
            "environment" to environment.toManifestMap(),
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

fun toolPolicy(block: ToolPolicyBuilder.() -> Unit): ToolPolicy =
    ToolPolicyBuilder().apply(block).build()

class ToolPolicyBuilder {
    var risk: ToolRisk = ToolRisk.LOW
    private var filesystem: ToolFilesystemPolicy = ToolFilesystemPolicy()
    private var network: ToolNetworkPolicy = ToolNetworkPolicy.Unspecified
    private var environment: ToolEnvironmentPolicy = ToolEnvironmentPolicy.Unspecified

    fun filesystem(block: ToolFilesystemPolicyBuilder.() -> Unit) {
        filesystem = ToolFilesystemPolicyBuilder(filesystem).apply(block).build()
    }

    fun network(block: ToolNetworkPolicyBuilder.() -> Unit) {
        network = ToolNetworkPolicyBuilder(network).apply(block).build()
    }

    fun environment(block: ToolEnvironmentPolicyBuilder.() -> Unit) {
        environment = ToolEnvironmentPolicyBuilder(environment).apply(block).build()
    }

    fun build(): ToolPolicy =
        ToolPolicy(
            risk = risk,
            filesystem = filesystem,
            network = network,
            environment = environment,
        )
}

class ToolFilesystemPolicyBuilder(initial: ToolFilesystemPolicy = ToolFilesystemPolicy()) {
    private val readGlobs = linkedSetOf<String>()
    private val writeGlobs = linkedSetOf<String>()
    private var readMode: Mode = Mode.UNSPECIFIED
    private var writeMode: Mode = Mode.UNSPECIFIED

    init {
        when (val read = initial.read) {
            is ToolFilesystemAccess.Globs -> {
                readMode = Mode.GLOBS
                readGlobs += read.globs
            }
            ToolFilesystemAccess.None -> readMode = Mode.NONE
            ToolFilesystemAccess.Unspecified -> Unit
        }
        when (val write = initial.write) {
            is ToolFilesystemAccess.Globs -> {
                writeMode = Mode.GLOBS
                writeGlobs += write.globs
            }
            ToolFilesystemAccess.None -> writeMode = Mode.NONE
            ToolFilesystemAccess.Unspecified -> Unit
        }
    }

    fun read(glob: String) {
        readMode = Mode.GLOBS
        readGlobs += nonBlank(glob, "filesystem read glob")
    }

    fun write(glob: String) {
        writeMode = Mode.GLOBS
        writeGlobs += nonBlank(glob, "filesystem write glob")
    }

    fun readNone() {
        readMode = Mode.NONE
        readGlobs.clear()
    }

    fun writeNone() {
        writeMode = Mode.NONE
        writeGlobs.clear()
    }

    fun build(): ToolFilesystemPolicy =
        ToolFilesystemPolicy(
            read = access(readMode, readGlobs.toList()),
            write = access(writeMode, writeGlobs.toList()),
        )

    private fun access(mode: Mode, globs: List<String>): ToolFilesystemAccess =
        when (mode) {
            Mode.UNSPECIFIED -> ToolFilesystemAccess.Unspecified
            Mode.NONE -> ToolFilesystemAccess.None
            Mode.GLOBS -> ToolFilesystemAccess.Globs(globs)
        }

    private enum class Mode { UNSPECIFIED, NONE, GLOBS }
}

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

private fun nonBlank(value: String, label: String): String {
    val trimmed = value.trim()
    require(trimmed.isNotEmpty()) { "$label must not be blank" }
    return trimmed
}

private object ManifestMaps {
    fun map(value: Any?): Map<*, *> = value as? Map<*, *> ?: emptyMap<Any?, Any?>()

    fun stringList(value: Any?): List<String> =
        when (value) {
            is Iterable<*> -> value.map { it.toString() }
            is Array<*> -> value.map { it.toString() }
            null -> emptyList()
            else -> listOf(value.toString())
        }
}

private object ManifestJson {
    fun encode(value: Any?): String = when (value) {
        null -> "null"
        is Boolean, is Number -> value.toString()
        is String -> quote(value)
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") { (key, mapValue) ->
            "${quote(key.toString())}:${encode(mapValue)}"
        }
        is Iterable<*> -> value.joinToString(",", "[", "]") { encode(it) }
        else -> quote(value.toString())
    }

    private fun quote(value: String): String =
        buildString(value.length + 2) {
            append('"')
            value.forEach { ch ->
                when (ch) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\b' -> append("\\b")
                    '\u000C' -> append("\\f")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> {
                        if (ch < ' ') append("\\u${ch.code.toString(16).padStart(4, '0')}") else append(ch)
                    }
                }
            }
            append('"')
        }
}

/**
 * #2805 — bespoke YAML round-trip used ONLY for the policy snapshot
 * embedded in the permission manifest. NOT a general-purpose YAML parser:
 * it knows only the closed shape `parsePolicyMap` ↔ `toManifestYaml`
 * produces. Adopting SnakeYAML would be heavier and would re-open a
 * supply-chain surface (#1916 sister concern) for one internal use.
 *
 * Depth constants name the legal indent levels for that closed shape:
 * top-level keys, sub-sections, leaf scalars/list-headers, and (only on
 * `filesystem`) the leaf level under `read:` / `write:`.
 */
private object ManifestYaml {
    // #2805 — named depth constants replace the literal `0/2/4/6` in
    // `when (indent)` so the shape is documented inline instead of mined
    // from the `+2 per level` indent convention by the next reader.
    private const val DEPTH_TOPLEVEL = 0
    private const val DEPTH_SECTION = 2
    private const val DEPTH_LEAF = 4
    private const val DEPTH_FILESYSTEM_LEAF = 6

    fun quote(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    fun parsePolicyMap(yaml: String): Map<String, Any?> {
        val root = linkedMapOf<String, Any?>()
        var section = ""
        var filesystemSide = ""
        var listTarget: MutableList<String>? = null

        yaml.lineSequence().forEach { raw ->
            val line = raw.trimEnd()
            if (line.isBlank()) return@forEach
            val indent = line.takeWhile { it == ' ' }.length
            val text = line.trim()
            if (text.startsWith("- ")) {
                listTarget?.add(unquote(text.removePrefix("- ").trim()))
                return@forEach
            }

            when (indent) {
                DEPTH_TOPLEVEL -> {
                    listTarget = null
                    if (text.startsWith("risk:")) {
                        root["risk"] = text.substringAfter(':').trim()
                    } else if (text.endsWith(":")) {
                        section = text.removeSuffix(":")
                        root[section] = linkedMapOf<String, Any?>()
                    }
                }
                DEPTH_SECTION -> {
                    listTarget = null
                    val sectionMap = root.getOrPutMap(section)
                    if (section == "filesystem" && text.endsWith(":")) {
                        filesystemSide = text.removeSuffix(":")
                        sectionMap[filesystemSide] = linkedMapOf<String, Any?>()
                    } else {
                        readScalarOrListHeader(sectionMap, text)?.let { listTarget = it }
                    }
                }
                DEPTH_LEAF -> {
                    val target = if (section == "filesystem") {
                        root.getOrPutMap(section).getOrPutMap(filesystemSide)
                    } else {
                        root.getOrPutMap(section)
                    }
                    readScalarOrListHeader(target, text)?.let { listTarget = it }
                }
                DEPTH_FILESYSTEM_LEAF -> {
                    val target = root.getOrPutMap(section).getOrPutMap(filesystemSide)
                    readScalarOrListHeader(target, text)?.let { listTarget = it }
                }
            }
        }
        return root
    }

    private fun readScalarOrListHeader(target: MutableMap<String, Any?>, text: String): MutableList<String>? {
        val key = text.substringBefore(':')
        val value = text.substringAfter(':', missingDelimiterValue = "").trim()
        return if (value.isEmpty()) {
            val list = mutableListOf<String>()
            target[key] = list
            list
        } else {
            target[key] = unquote(value)
            null
        }
    }

    private fun MutableMap<String, Any?>.getOrPutMap(key: String): MutableMap<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return getOrPut(key) { linkedMapOf<String, Any?>() } as MutableMap<String, Any?>
    }

    private fun unquote(value: String): String {
        if (value.length < 2 || value.first() != '"' || value.last() != '"') return value
        val body = value.substring(1, value.length - 1)
        val out = StringBuilder()
        var i = 0
        while (i < body.length) {
            val ch = body[i++]
            if (ch == '\\' && i < body.length) {
                out.append(body[i++])
            } else {
                out.append(ch)
            }
        }
        return out.toString()
    }
}
