package agents_engine.core

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

internal fun buildMemoryTools(bank: MemoryBank, agentName: String): List<ToolDef> {
    val read = ToolDef("memory_read", "Read agent memory. Returns the stored memory content.") { _ ->
        bank.read(agentName)
    }

    val write = ToolDef("memory_write", "Write to agent memory. Argument: content (string). Overwrites current memory.") { args ->
        val content = args["content"]?.toString()
            ?: args.values.firstOrNull()?.toString()
            ?: ""
        bank.write(agentName, content)
        "ok"
    }

    val search = ToolDef("memory_search", "Search agent memory for lines matching a query. Argument: query (string). Returns matching lines.") { args ->
        val query = args["query"]?.toString()
            ?: args.values.firstOrNull()?.toString()
            ?: ""
        val content = bank.read(agentName)
        if (content.isBlank() || query.isBlank()) ""
        else content.lines()
            .filter { it.contains(query, ignoreCase = true) }
            .joinToString("\n")
    }

    return listOf(read, write, search)
}
