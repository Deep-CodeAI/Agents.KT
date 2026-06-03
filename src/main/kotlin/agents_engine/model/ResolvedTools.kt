package agents_engine.model

import agents_engine.core.Agent
import agents_engine.core.Skill

/**
 * #3423 — the resolved tool set for one agentic invocation, extracted from `executeAgentic`'s inline
 * setup block. The four members are exactly what the turn loop downstream consumes:
 *  - [allToolDefs]: action + knowledge tools, advertised to the model and used to shape the client
 *    (tool schemas, [buildSystemPrompt]).
 *  - [knowledgeToolDefs]: the lazy knowledge-on-demand subset (drives the description-only system
 *    prompt and is dispatched separately from action tools).
 *  - [knowledgeToolMap]: [knowledgeToolDefs] keyed by name, for per-call knowledge lookup.
 *  - [allowedToolMap]: the **authorization boundary** (#630) — execution resolves emitted tool names
 *    against THIS map only, never the wider `agent.toolMap`.
 */
internal data class ResolvedTools(
    val allToolDefs: List<ToolDef>,
    val knowledgeToolDefs: List<ToolDef>,
    val knowledgeToolMap: Map<String, ToolDef>,
    val allowedToolMap: Map<String, ToolDef>,
)

/**
 * #3423 — assembles the allowed tool set for [skill] on [agent]: skill-declared tools + agent
 * capability tools + per-skill memory tools (#856), plus lazily-exposed knowledge tools, with a
 * fail-fast on duplicate names across sources (#645). Pure aside from invoking the agent's tool
 * factories. Extracted verbatim from `executeAgentic` so behavior is unchanged.
 */
internal fun resolveAllowedTools(agent: Agent<*, *>, skill: Skill<*, *>): ResolvedTools {
    // Action tools: tools the skill explicitly lists + agent capabilities + memory tools
    val skillToolDefs = skill.toolNames?.mapNotNull { agent.toolMap[it] } ?: emptyList()
    val autoToolDefs = agent.autoToolNames.mapNotNull { agent.toolMap[it] }
    // #856 — memory-tool authorization is per-skill when ANY skill opts in via
    // `useMemory()`. If none opt in, fall back to the legacy default-on behavior
    // (every skill gets memory tools when memoryBank is set) so existing
    // single-skill agents don't break.
    val anySkillOptedIntoMemory = agent.skills.values.any { it.useMemory }
    val memoryToolDefs = when {
        agent.memoryBank == null -> emptyList()
        anySkillOptedIntoMemory && !skill.useMemory -> emptyList()
        // #2804 — reuse RESERVED_MEMORY_TOOL_NAMES (ToolDef.kt) so adding a
        // 4th memory tool (e.g. memory_delete) only updates one set, not two.
        else -> agent.toolMap.values.filter { it.name in RESERVED_MEMORY_TOOL_NAMES }
    }
    val actionToolDefs = (skillToolDefs + autoToolDefs + memoryToolDefs).distinctBy { it.name }

    // Knowledge tools: exposed lazily — LLM calls them to load context on demand
    val knowledgeToolDefs = skill.knowledgeTools().map { kt ->
        ToolDef(kt.name, kt.description) { _ -> kt.call() }
    }
    val knowledgeToolMap = knowledgeToolDefs.associateBy { it.name }

    val allToolDefs = actionToolDefs + knowledgeToolDefs

    // Fail-fast on duplicate tool names across the allowed sources (skill tools,
    // auto tools, memory tools, knowledge entries). `distinctBy` would silently
    // pick a winner; we want this surfaced as a configuration error. See #645.
    val duplicateNames = allToolDefs.groupBy { it.name }.filterValues { it.size > 1 }.keys
    check(duplicateNames.isEmpty()) {
        "Duplicate tool names in allowed tool set for skill '${skill.name}': $duplicateNames. " +
            "A name appears in more than one source (skill tools, auto tools, memory tools, " +
            "knowledge entries) — pick one source per name."
    }

    // Authorization boundary: execution looks up against THIS allowlist only,
    // not the wider agent.toolMap. A model emitting any tool name not in this
    // map will be refused — even if the agent has that tool registered for a
    // different skill. This is the runtime enforcement the prompt does NOT do.
    val allowedToolMap = allToolDefs.associateBy { it.name }

    return ResolvedTools(allToolDefs, knowledgeToolDefs, knowledgeToolMap, allowedToolMap)
}
