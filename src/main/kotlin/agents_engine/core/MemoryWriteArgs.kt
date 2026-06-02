package agents_engine.core

import agents_engine.generation.Generable
import agents_engine.generation.Guide

/**
 * #2379 — typed args for `memory_write`. Generates a proper JSON Schema
 * via `argsType` instead of relying on the LLM to infer the shape from
 * the description prose. Unblocks safely closing the wire-format
 * tool-schema fallback in a future revisit of #2377.
 */
@Generable("Arguments for memory_write — overwrites the agent's memory slot.")
data class MemoryWriteArgs(
    @Guide("The full content to store. Overwrites whatever was there before.")
    val content: String,
)
