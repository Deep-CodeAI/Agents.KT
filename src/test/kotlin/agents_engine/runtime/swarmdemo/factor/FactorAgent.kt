package agents_engine.runtime.swarmdemo.factor

import agents_engine.core.Agent
import agents_engine.core.agent
import agents_engine.runtime.AgentProvider

// factor.jar — sibling agent for the swarm demo (#984). Self-contained:
// own copies of helpers so the JAR doesn't depend on any sibling's classes.

private const val MODEL = "gpt-oss:120b-cloud"
private const val HOST  = "localhost"
private const val PORT  = 11434

internal fun traceTool(agentName: String, toolName: String, args: Map<String, Any?>, result: Any?) {
    val argsStr = if (args.isEmpty()) "" else args.entries.joinToString(", ") { "${it.key}=${it.value}" }
    val resultStr = result?.toString()?.let { if (it.length > 80) it.take(77) + "..." else it } ?: "null"
    System.err.println("  [$agentName] $toolName($argsStr) → $resultStr")
}

internal fun readInt(args: Map<String, Any?>, key: String): Int {
    val v = args[key] ?: args.values.firstOrNull()
        ?: error("missing argument: $key")
    return when (v) {
        is Number -> v.toInt()
        is String -> v.trim().toIntOrNull() ?: error("'$v' is not an integer")
        else -> error("'$v' is not an integer")
    }
}

internal fun primeFactors(nIn: Int): List<Int> {
    var n = nIn
    val result = mutableListOf<Int>()
    var p = 2
    while (p.toLong() * p.toLong() <= n) {
        while (n % p == 0) { result += p; n /= p }
        p++
    }
    if (n > 1) result += n
    return result
}

fun buildFactorAgent(): Agent<String, String> = agent("factor") {
    prompt("""
        You factor integers into prime factors.
        Use the `factor_number` tool with argument n (integer ≥ 2).
        Reply with the comma-separated prime factors, nothing else.
    """.trimIndent())
    model { ollama(MODEL); host = HOST; port = PORT; temperature = 0.0 }
    tools {
        tool("factor_number", "Compute the prime factors of n. Argument: n (integer ≥ 2).") { args ->
            val n = readInt(args, "n")
            require(n >= 2) { "n must be ≥ 2 to factor" }
            primeFactors(n).joinToString(", ")
        }
    }
    skills {
        skill<String, String>("factor", "Prime-factor an integer") {
            tools("factor_number")
        }
    }
    onToolUse { name, args, result -> traceTool("factor", name, args, result) }
}

class FactorProvider : AgentProvider {
    override fun build(): Agent<*, *> = buildFactorAgent()
}
