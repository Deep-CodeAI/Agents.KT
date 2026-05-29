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
class MemoryBank(val maxLines: Int = Int.MAX_VALUE) : Snapshotable<Map<String, String>> {

    private val store = ConcurrentHashMap<String, String>()

    fun read(key: String): String = store[key] ?: ""

    fun write(key: String, content: String) {
        store[key] = truncate(content)
    }

    fun entries(): Map<String, String> = store.toMap()

    // #2416 — snapshot/restore for persistence. Values were already truncated
    // on write, so restore replays them verbatim.
    override fun snapshot(): Map<String, String> = entries()

    /**
     * @deprecated #2755 — wipes the entire backing store. When two agents share
     *   a bank (the documented "shared workspace" topology), this destroys the
     *   other agent's slots. Callers participating in snapshot/resume should
     *   use [snapshotForAgent] / [restoreForAgent] instead. Kept for the
     *   Snapshotable<Map<String,String>> interface contract but marked
     *   internal so accidental call sites surface as compile errors.
     */
    @Deprecated("Use restoreForAgent to avoid wiping unrelated agents' slots in a shared bank (#2755).")
    override fun restore(state: Map<String, String>) {
        store.clear()
        store.putAll(state)
    }

    /**
     * #2755 — per-agent snapshot. `MemoryBank` keys each slot by `agentName`,
     * so the snapshot is just that one entry. A shared-bank topology can
     * snapshot/resume one agent's session without disturbing the other
     * agents' slots.
     *
     * Returns `null` if the agent has never written to the bank.
     */
    fun snapshotForAgent(agentName: String): String? = store[agentName]

    /**
     * #2755 — per-agent restore. Only touches the slot for `agentName`.
     * `null` clears that slot; a non-null value replaces it. Other agents'
     * slots in the same shared bank are untouched.
     */
    fun restoreForAgent(agentName: String, value: String?) {
        if (value == null) store.remove(agentName) else store[agentName] = value
    }

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
            // #2379 — prefer the typed args, but fall back to lenient
            // single-value extraction when the model passes an unexpected key,
            // preserving the pre-typed behavior (AgentMemoryTest "any arg key").
            val content = MemoryWriteArgs::class.constructFromMap(args)?.content
                ?: args["content"]?.toString()
                ?: args.values.firstOrNull()?.toString()
                ?: ""
            bank.write(agentName, content)
            "ok"
        },
    )

    val search = ToolDef(
        name = "memory_search",
        description = "Search agent memory for lines matching a query. Returns matching lines.",
        argsType = MemorySearchArgs::class,
        executor = { args ->
            // #2379 — typed args first, lenient single-value fallback second
            // (see memory_write above).
            val query = MemorySearchArgs::class.constructFromMap(args)?.query
                ?: args["query"]?.toString()
                ?: args.values.firstOrNull()?.toString()
                ?: ""
            val content = bank.read(agentName)
            if (content.isBlank() || query.isBlank()) ""
            else content.lines()
                .filter { it.contains(query, ignoreCase = true) }
                .joinToString("\n")
        },
    )

    return listOf(read, write, search)
}
