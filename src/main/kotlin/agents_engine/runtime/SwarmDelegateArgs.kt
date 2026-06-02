package agents_engine.runtime

import agents_engine.generation.Generable
import agents_engine.generation.Guide

/**
 * #2379 — typed args for tools minted by [Agent.absorb]. v1 of absorb
 * only supports `Agent<String, *>`, so the delegate tool's input shape
 * is always a single `query: String`. Having a real `argsType` here lets
 * the wire-format provider clients emit a proper JSON Schema instead of
 * falling back to the permissive empty-properties form.
 */
@Generable("Arguments for a swarm-delegate tool — forward a single-string query to the sibling agent.")
data class SwarmDelegateArgs(
    @Guide("Free-text query for the sibling agent. The sibling's first skill must accept String input.")
    val query: String,
)
