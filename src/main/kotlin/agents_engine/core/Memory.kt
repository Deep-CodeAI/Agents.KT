package agents_engine.core

import agents_engine.generation.Generable
import agents_engine.generation.Guide
import agents_engine.generation.constructFromMap
import agents_engine.model.ToolDef
import java.util.concurrent.ConcurrentHashMap

/**
 * `agents_engine/core/Memory.kt` — agent memory bank + the `memory_*` tool family.
 *
 * **MemoryBank.** A `ConcurrentHashMap<String, String>` keyed by agent name.
 * Each agent reads/writes under its own name; sharing memory across agents
 * is opt-in (pass the same bank to multiple agents).
 *
 * **Sharing model.** One bank per agent = isolation. One bank shared across
 * many agents = shared workspace (each writes under its own name, all can
 * read each other's slots via different keys if needed). The framework does
 * not assume a particular topology.
 *
 * **Bounded line history.** `maxLines` truncates each write to the LAST N
 * lines. Default is `Int.MAX_VALUE` (unbounded). Useful for streaming
 * scratch-pads where the LLM should only see recent state.
 *
 * **Three built-in tools** (built by [buildMemoryTools]):
 * - `memory_read` — no args, returns stored content as String.
 * - `memory_write` — arg `content` (or first value), overwrites the agent's slot.
 * - `memory_search` — arg `query` (or first value), case-insensitive line filter.
 *
 * **Opt-in (#856).** Tools are exposed only to skills that called
 * `Skill.useMemory()`. When NO skill opts in, the legacy behavior applies:
 * every skill gets memory if a bank is set.
 *
 * See `src/main/resources/internals-agent/core/Memory.md` for the adjunct
 * surfaced to IDE-side LLM tools via `agents-kt-internals` (#1837 / #1840).
 */
class MemoryBank(val maxLines: Int = Int.MAX_VALUE) {

    private val store = ConcurrentHashMap<String, String>()

    fun read(key: String): String = store[key] ?: ""

    fun write(key: String, content: String) {
        store[key] = truncate(content)
    }

    fun entries(): Map<String, String> = store.toMap()

    private fun truncate(content: String): String {
        if (maxLines == Int.MAX_VALUE) return content
        val lines = content.lines()
        return if (lines.size > maxLines) lines.takeLast(maxLines).joinToString("\n")
        else content
    }
}

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

/**
 * `parametersSchemaJson` for tools that genuinely take no arguments.
 * Pre-#2379 these tools relied on the permissive empty-properties
 * fallback in the provider clients; with this schema the wire format
 * is explicit and matches the future closed-fallback behavior, so the
 * fallback's policy can be tightened without regressing these tools.
 */
private const val NO_ARGS_SCHEMA =
    """{"type":"object","properties":{},"additionalProperties":false}"""

internal fun buildMemoryTools(bank: MemoryBank, agentName: String): List<ToolDef> {
    val read = ToolDef(
        name = "memory_read",
        description = "Read agent memory. Returns the stored memory content.",
        parametersSchemaJson = NO_ARGS_SCHEMA,
        executor = { _ -> bank.read(agentName) },
    )

    val write = ToolDef(
        name = "memory_write",
        description = "Write to agent memory. Overwrites whatever was previously stored.",
        argsType = MemoryWriteArgs::class,
        executor = { args ->
            val typed = MemoryWriteArgs::class.constructFromMap(args)
                ?: error("memory_write received malformed args: $args")
            bank.write(agentName, typed.content)
            "ok"
        },
    )

    val search = ToolDef(
        name = "memory_search",
        description = "Search agent memory for lines matching a query. Returns matching lines.",
        argsType = MemorySearchArgs::class,
        executor = { args ->
            val typed = MemorySearchArgs::class.constructFromMap(args)
                ?: error("memory_search received malformed args: $args")
            val content = bank.read(agentName)
            if (content.isBlank() || typed.query.isBlank()) ""
            else content.lines()
                .filter { it.contains(typed.query, ignoreCase = true) }
                .joinToString("\n")
        },
    )

    return listOf(read, write, search)
}
