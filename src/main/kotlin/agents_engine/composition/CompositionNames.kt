package agents_engine.composition

import agents_engine.core.Agent

/**
 * #4500 — concurrent composition (`Parallel` via `/`, `Forum` via `*`) streams every leg's
 * events into ONE emitter, demultiplexable only by `agentId` (= the agent's name). Two legs
 * sharing a name produce interleaved, indistinguishable streams. The single-placement rule
 * (`markPlaced`) catches the *same instance* placed twice but not two distinct same-named
 * instances, so this guard fails loud at construction — the stance the framework already takes
 * for duplicate tool and skill names.
 */
internal fun requireDistinctAgentNames(agents: List<Agent<*, *>>, operator: String) {
    val duplicates = agents.map { it.name }
        .groupingBy { it }
        .eachCount()
        .filterValues { it > 1 }
        .keys
    require(duplicates.isEmpty()) {
        "$operator participants must have distinct names — streamed events are demultiplexed by " +
            "agentId (the agent's name), so duplicates ${duplicates.joinToString(prefix = "[", postfix = "]")} " +
            "would collide. Rename one, or use speculative(n) for deliberate self-racing."
    }
}
