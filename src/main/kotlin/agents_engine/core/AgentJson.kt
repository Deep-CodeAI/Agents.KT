package agents_engine.core

import agents_engine.agntcy.OasfLocator
import agents_engine.mcp.McpJson

/**
 * #4516 (PRD §12.2) — serialize an agent's **definition** to the `agent.json` document: a
 * declarative, deterministic snapshot of name, the types it consumes/produces, its skills, its
 * tools, and capabilities. Distinct from the permission *manifest* (which is the security/audit
 * artifact) and the A2A AgentCard (the network discovery artifact) — this is the portable
 * description of the agent itself.
 *
 * Keys are emitted in a fixed order, so the same agent always serializes byte-identically.
 *
 * #4518 (PRD §12.6) — optional provenance fields ([authors], [createdAt], [locators]) are shared with
 * the OASF record (`toOasfRecord`); they are additive and omitted when not supplied, so existing
 * callers serialize byte-identically.
 */
fun Agent<*, *>.toAgentJson(
    version: String? = null,
    description: String? = null,
    authors: List<String> = emptyList(),
    createdAt: String? = null,
    locators: List<OasfLocator> = emptyList(),
): String {
    val metadata = LinkedHashMap<String, Any?>()
    metadata["name"] = name
    if (version != null) metadata["version"] = version
    if (description != null) metadata["description"] = description
    if (authors.isNotEmpty()) metadata["authors"] = authors
    if (createdAt != null) metadata["createdAt"] = createdAt

    val skillDocs = skills.values
        .sortedBy { it.name }
        .map { skill ->
            linkedMapOf<String, Any?>(
                "name" to skill.name,
                "description" to skill.description,
                "consumes" to typeName(skill.inType),
                "produces" to typeName(skill.outType),
            )
        }

    val toolDocs = toolMap.values
        .sortedBy { it.name }
        .map { tool ->
            linkedMapOf<String, Any?>(
                "name" to tool.name,
                "description" to tool.description,
                "risk" to tool.risk.name,
            )
        }

    val types = linkedMapOf<String, Any?>(
        "consumes" to skills.values.map { typeName(it.inType) }.distinct().sorted(),
        "produces" to typeName(outType),
    )

    val spec = linkedMapOf<String, Any?>(
        "types" to types,
        "skills" to skillDocs,
        "tools" to toolDocs,
        "capabilities" to linkedMapOf<String, Any?>("streaming" to true),
    )
    if (locators.isNotEmpty()) {
        spec["locators"] = locators.map { linkedMapOf<String, Any?>("type" to it.type, "urls" to it.urls) }
    }

    val doc = linkedMapOf<String, Any?>(
        "apiVersion" to AGENT_JSON_API_VERSION,
        "kind" to "Agent",
        "metadata" to metadata,
        "spec" to spec,
    )

    return McpJson.encode(doc)
}

internal fun typeName(klass: kotlin.reflect.KClass<*>): String =
    runCatching { klass.qualifiedName }.getOrNull() ?: klass.java.name

internal const val AGENT_JSON_API_VERSION = "agents-kt/v1"
