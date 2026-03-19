package agents_engine.core

import agents_engine.model.ToolDef
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory store for agent memory. Each agent writes under its own name as key.
 * Shared across agents — pass the same bank to multiple agents for shared context,
 * or give each agent its own bank for isolation.
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

    return listOf(read, write)
}
