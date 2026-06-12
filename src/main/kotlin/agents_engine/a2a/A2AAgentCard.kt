package agents_engine.a2a

import agents_engine.core.Agent
import agents_engine.core.Skill
import agents_engine.generation.LenientJsonParser
import agents_engine.generation.hasGenerableAnnotation
import agents_engine.generation.jsonSchema

/**
 * #3864 — builds the A2A AgentCard document published at
 * `/.well-known/agent-card.json`. Pinned to A2A protocol version 0.2;
 * skills come from the agent's declared skills, each with the
 * `@Generable` JSON Schema of its IN type when available
 * (`{"input": string}` otherwise — same convention as the MCP server).
 */
internal fun agentCard(agent: Agent<*, *>, url: String): Map<String, Any?> = linkedMapOf(
    "protocolVersion" to A2A_PROTOCOL_VERSION,
    "name" to agent.name,
    "description" to (agent.skills.values.firstOrNull()?.description ?: ""),
    "url" to url,
    "capabilities" to mapOf(
        // Streaming over A2A is a follow-up — message/send only in v1.
        "streaming" to false,
        "pushNotifications" to false,
    ),
    "defaultInputModes" to listOf("text"),
    "defaultOutputModes" to listOf("text"),
    "skills" to agent.skills.values.map { skill ->
        linkedMapOf(
            "id" to skill.name,
            "name" to skill.name,
            "description" to skill.description,
            "inputSchema" to inputSchemaFor(skill),
        )
    },
)

private fun inputSchemaFor(skill: Skill<*, *>): Any =
    if (skill.inType.hasGenerableAnnotation()) {
        LenientJsonParser.parse(skill.inType.jsonSchema()) ?: emptyMap<String, Any?>()
    } else {
        mapOf(
            "type" to "object",
            "properties" to mapOf("input" to mapOf("type" to "string")),
            "required" to listOf("input"),
        )
    }

internal const val A2A_PROTOCOL_VERSION = "0.2"
