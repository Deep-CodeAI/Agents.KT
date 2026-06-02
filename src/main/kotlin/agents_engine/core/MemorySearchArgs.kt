package agents_engine.core

import agents_engine.generation.Generable
import agents_engine.generation.Guide

/**
 * #2379 — typed args for `memory_search`. Same rationale as
 * [MemoryWriteArgs] — the LLM gets a real schema instead of having to
 * parse the description prose.
 */
@Generable("Arguments for memory_search — returns the lines that contain the query substring.")
data class MemorySearchArgs(
    @Guide("Case-insensitive substring to look for in stored memory lines.")
    val query: String,
)
