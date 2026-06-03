package agents_engine.model

import agents_engine.core.Agent
import agents_engine.core.Skill
import agents_engine.core.SkillRoute
import agents_engine.generation.fromLlmOutput
import agents_engine.generation.jsonSchema
import agents_engine.generation.toLlmInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Asks the LLM to pick a skill from [candidates]. Returns a structured [SkillRoute]
 * with name, confidence, and rationale (#641). When the model returns plain text
 * (older / smaller models), falls back to treating it as a skill name with
 * confidence = 1.0.
 */
suspend fun <IN> selectSkillByLlm(
    agent: Agent<IN, *>,
    candidates: List<Skill<*, *>>,
    input: IN,
): SkillRoute {
    val config = requireNotNull(agent.modelConfig) {
        "Agent '${agent.name}' has no model configured for LLM skill selection."
    }

    val systemPrompt = buildString {
        appendLine("You are a skill router. Given the user's input, pick the most appropriate skill.")
        appendLine()
        appendLine("Available skills:")
        candidates.forEach { skill ->
            appendLine()
            appendLine(skill.toLlmDescription())
        }
        appendLine()
        appendLine("Respond ONLY with this JSON shape:")
        appendLine("""{"skillName": "<one of the listed skills>", "confidence": 0.0..1.0, "rationale": "<one sentence>"}""")
    }

    val messages = listOf(
        LlmMessage("system", systemPrompt),
        LlmMessage("user", toLlmInput(input)),  // #937 — typed Generable inputs as JSON
    )

    // Skill-routing round-trip is its own LLM call; caching here is rarely
    // useful (skill descriptions are highly variable), so no routing key.
    val client = config.client ?: ModelClientFactory.defaultClientFor(config, emptyList(), promptCacheKey = null)
    val routeSchema = if (client.supportsConstrainedDecoding()) {
        JsonSchema("SkillRoute", SkillRoute::class.jsonSchema())
    } else null
    val response = withContext(Dispatchers.IO) { client.chat(messages, routeSchema) }

    val raw = when (response) {
        is LlmResponse.Text -> response.content.trim()
        is LlmResponse.ToolCalls -> error("Expected text response for skill selection, got tool calls")
    }

    return SkillRoute::class.fromLlmOutput(raw)
        ?: SkillRoute(skillName = raw, confidence = 1.0, rationale = "")  // raw-text fallback
}
