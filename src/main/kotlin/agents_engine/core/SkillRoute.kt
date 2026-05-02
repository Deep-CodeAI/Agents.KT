package agents_engine.core

import agents_engine.generation.Generable
import agents_engine.generation.Guide

/**
 * Structured result the LLM router returns when picking a skill from a list of
 * candidates. See #641.
 *
 * - [skillName] must match one of the candidate skills.
 * - [confidence] is checked against the agent's `skillSelectionConfidenceThreshold`
 *   (default 0.6); below threshold throws [SkillRoutingException].
 * - [rationale] surfaces via the optional `routerRationale { }` observability
 *   hook on the agent.
 */
@Generable("Skill routing decision")
data class SkillRoute(
    @Guide("Name of the chosen skill from the available list") val skillName: String,
    @Guide("0.0 to 1.0 — how confident the router is in this choice") val confidence: Double,
    @Guide("One short sentence explaining the choice") val rationale: String,
)

class SkillRoutingException(message: String) : RuntimeException(message)
