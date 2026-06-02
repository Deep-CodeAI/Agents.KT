package agents_engine.core

import agents_engine.model.selectSkillByLlm

/**
 * Resolves which [Skill] an [Agent] invocation dispatches to (#3088 stage 2, de-slop #3083).
 *
 * Extracted from `Agent.kt`'s God-object body: the type-compatible-candidate filter, the manual
 * `skillSelection { }` selector path, the LLM-router path (with its confidence gate), the
 * before-skill-interceptor `ProceedWith` compatibility check, and the fail-loud ambiguity error
 * (#3087) all live here now. [Agent] keeps only thin delegating call sites.
 *
 * Behavior-preserving: every branch order, `check`/`error` condition, exception type, and message
 * is identical to the previous in-`Agent` implementation — this is a move, not a redesign.
 */
internal class SkillResolver<IN, OUT>(private val agent: Agent<IN, OUT>) {

    private fun candidatesFor(input: IN): List<Skill<*, *>> =
        agent.skills.values.filter { it.inType.java.isInstance(input) && it.outType == agent.outType }

    /**
     * The skill to run for [input]: a manual `skillSelection { }` selector wins if set; otherwise
     * resolve among type-compatible candidates (single → that one, multiple + model → LLM router,
     * multiple + no model/selector → fail loud).
     */
    suspend fun resolve(input: IN): Skill<*, *> {
        val candidates = candidatesFor(input)

        agent.skillSelector?.let { selector ->
            val selectedName = selector(input)
            val selected = agent.skills[selectedName] ?: error(
                "skillSelection returned unknown skill name \"$selectedName\". " +
                    "Available: ${agent.skills.keys}"
            )
            check(selected in candidates) {
                "skillSelection returned incompatible skill \"$selectedName\". " +
                    "Compatible skills for agent \"${agent.name}\": ${candidates.map { it.name }}"
            }
            return selected
        }

        return when {
            candidates.isEmpty() -> error(
                "Agent \"${agent.name}\" has no skill for ${agent.outType.simpleName}. " +
                    "Add a skill with implementedBy { } block."
            )
            candidates.size == 1 -> candidates.single()
            agent.modelConfig != null -> routeByLlm(candidates, input)
            else -> ambiguousSkillRoutingError(candidates)
        }
    }

    private suspend fun routeByLlm(candidates: List<Skill<*, *>>, input: IN): Skill<*, *> {
        val route = selectSkillByLlm(agent, candidates, input)
        if (route.confidence < agent.skillSelectionConfidenceThreshold) {
            throw SkillRoutingException(
                "Router uncertain (confidence=${route.confidence}, " +
                    "threshold=${agent.skillSelectionConfidenceThreshold}). Rationale: ${route.rationale}"
            )
        }
        val selected = candidates.find { it.name == route.skillName }
            ?: throw SkillRoutingException(
                "LLM router selected unknown skill \"${route.skillName}\". " +
                    "Available: ${candidates.map { it.name }}. Rationale: ${route.rationale}"
            )
        if (route.rationale.isNotEmpty()) agent.routerRationaleListener?.invoke(route.rationale)
        return selected
    }

    /**
     * Resolve the skill a before-skill interceptor's `ProceedWith` named, validating it accepts the
     * invocation input and produces the agent's output type.
     */
    fun compatible(skillName: String, input: IN): Skill<*, *> {
        val selected = agent.skills[skillName] ?: error(
            "before-skill interceptor returned unknown skill name \"$skillName\". " +
                "Available: ${agent.skills.keys}"
        )
        check(selected.inType.java.isInstance(input) && selected.outType == agent.outType) {
            "before-skill interceptor returned incompatible skill \"$skillName\". " +
                "Compatible skills for agent \"${agent.name}\" must accept the invocation input " +
                "and produce ${agent.outType.simpleName}."
        }
        return selected
    }

    /**
     * Multiple compatible skills, but no selector and no model to choose between them. Fail loud
     * rather than silently routing to the first by registration order — routing in an auditable
     * runtime must be explicit (#3087).
     */
    private fun ambiguousSkillRoutingError(candidates: List<Skill<*, *>>): Nothing =
        throw SkillRoutingException(
            "Agent \"${agent.name}\" has ${candidates.size} compatible skills for ${agent.outType.simpleName} " +
                "(${candidates.joinToString { "\"${it.name}\"" }}) but no way to choose between them. " +
                "Add an explicit skillSelection { } selector, or configure a model { } for LLM routing. " +
                "Silent first-match routing is disallowed — routing must be explicit and auditable."
        )
}
