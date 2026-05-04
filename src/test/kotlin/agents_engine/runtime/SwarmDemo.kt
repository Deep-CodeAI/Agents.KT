package agents_engine.runtime

import agents_engine.core.Agent
import agents_engine.core.agent
import kotlin.system.exitProcess

// Manual swarm demo for #984. Three sibling agents share one JVM via
// ServiceLoader; the demo's main() picks `fib` as captain and absorbs the
// others. From the captain's REPL the user can:
//
//   fib> compute fib(10)            -> uses captain's own `fibonacci` tool
//   fib> factor 84                  -> delegates to the `factor` sibling
//   fib> bye, close the app please  -> delegates to the `exit` sibling
//
// Lives under src/test/kotlin so it never ships in the published JAR.
// Wired up via the `swarmDemo` Gradle task.
//
// Prerequisites:
//   - Ollama running (localhost:11434)
//   - `ollama signin` (cloud variant)  OR change MODEL below to a local one

private const val MODEL = "gpt-oss:120b-cloud"
private const val HOST  = "localhost"
private const val PORT  = 11434

/** The three demo agent names — used to filter Swarm.discover output. */
internal val SWARM_DEMO_NAMES = setOf("fib", "factor", "exit")

/** Compact tool-trace formatter shared by all three demo agents. */
private fun traceTool(agentName: String, toolName: String, args: Map<String, Any?>, result: Any?) {
    val argsStr = if (args.isEmpty()) "" else args.entries.joinToString(", ") { "${it.key}=${it.value}" }
    val resultStr = result?.toString()?.let { if (it.length > 80) it.take(77) + "..." else it } ?: "null"
    System.err.println("  [$agentName] $toolName($argsStr) → $resultStr")
}

internal fun buildFibAgent(): Agent<String, String> = agent("fib") {
    prompt("""
        You are a router-style assistant. Inspect the user's request
        and pick the right tool; do NOT answer in plain text when a
        tool is appropriate.

        Your tools:
        - `fibonacci` — compute the nth Fibonacci. Use for any "fib(n)"
          or "Fibonacci" request.
        - `factor` — delegate to the factor sibling. Use whenever the
          user says "factor", "prime factors", or asks to break a number
          down into its prime factorization. Call it with the user's
          original request as the `query` argument.
        - `exit` — delegate to the exit sibling. Use whenever the user
          says exit / quit / leave / close / bye / goodbye / "I'm done".
          Call it with the user's original request as the `query`
          argument. NEVER reply with "session closed" in plain text;
          ALWAYS call the `exit` tool.

        After a tool returns, render the result in 1–2 short sentences.
    """.trimIndent())
    model { ollama(MODEL); host = HOST; port = PORT; temperature = 0.0 }
    tools {
        tool("fibonacci", "Compute the nth Fibonacci number. Argument: n (integer ≥ 0).") { args ->
            val n = readInt(args, "n")
            fib(n).toString()
        }
    }
    skills {
        skill<String, String>("compute", "Run a math calculation, possibly via tools") {
            tools("fibonacci")
        }
    }
    onToolUse { name, args, result -> traceTool("fib", name, args, result) }
}

internal fun buildFactorAgent(): Agent<String, String> = agent("factor") {
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

internal fun buildExitAgent(): Agent<String, String> = agent("exit") {
    prompt("""
        You have exactly ONE tool: `exit_app`. Call it with no arguments
        when the user wants to close, exit, quit, leave, or end the
        session. Do NOT call it for other requests.
    """.trimIndent())
    model { ollama(MODEL); host = HOST; port = PORT; temperature = 0.0 }
    tools {
        tool("exit_app", "Close the application. Use when the user asks to exit, quit, or end.") { _ ->
            println()
            println("(exit agent — shutting down)")
            exitProcess(0)
        }
    }
    skills {
        skill<String, String>("close", "End the session on user request") {
            tools("exit_app")
        }
    }
    onToolUse { name, args, result -> traceTool("exit", name, args, result) }
}

private fun fib(n: Int): Long {
    require(n >= 0) { "n must be ≥ 0" }
    var a = 0L; var b = 1L
    repeat(n) { val t = a + b; a = b; b = t }
    return a
}

private fun primeFactors(nIn: Int): List<Int> {
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

/**
 * Read an integer arg from the executor's args map. Tools receive arbitrary
 * `Map<String, Any?>` from the LLM — the model may pass `n` as Int, Long,
 * Double, or as a stringified number. Coerce all of those.
 */
private fun readInt(args: Map<String, Any?>, key: String): Int {
    val v = args[key] ?: args.values.firstOrNull()
        ?: error("missing argument: $key")
    return when (v) {
        is Number -> v.toInt()
        is String -> v.trim().toIntOrNull() ?: error("'$v' is not an integer")
        else -> error("'$v' is not an integer")
    }
}

// ─── ServiceLoader providers ─────────────────────────────────────────────

class SwarmDemoFibProvider : AgentProvider {
    override fun build(): Agent<*, *> = buildFibAgent()
}

class SwarmDemoFactorProvider : AgentProvider {
    override fun build(): Agent<*, *> = buildFactorAgent()
}

class SwarmDemoExitProvider : AgentProvider {
    override fun build(): Agent<*, *> = buildExitAgent()
}

// ─── Captain main() ──────────────────────────────────────────────────────

fun main(args: Array<String>) {
    // Discover all swarm members in one shot. Filter to just the three demo
    // names so we don't accidentally absorb the in-test fixture provider that
    // also lives on the test classpath.
    val members = Swarm.discover().filter { it.name in SWARM_DEMO_NAMES }
    val me = members.singleOrNull { it.name == "fib" }
        ?: error("captain 'fib' not found among discovered providers: ${members.map { it.name }}")
    val siblings = members.filter { it.name != me.name }

    println()
    println("============================================================")
    println("Swarm demo (#984) — captain: ${me.name}")
    println("Discovered ${siblings.size} siblings: ${siblings.joinToString { it.name }}")
    println("Each sibling carries its own personality (prompt + tools);")
    println("the captain absorbed them as tools and can call them via")
    println("its LLM-driven dispatch.")
    println()
    println("Try:")
    println("  fib> what's fib(10)?")
    println("  fib> factor 84")
    println("  fib> bye, please exit the app")
    println("============================================================")
    println()

    siblings.forEach { sibling -> me.absorb(sibling) }

    // me came back from Swarm.discover as Agent<*, *>; narrow to the
    // String-input overload of LiveRunner.serve. Safe because the demo
    // built it from buildFibAgent(), which is Agent<String, String>.
    @Suppress("UNCHECKED_CAST")
    val captain = me as Agent<String, String>
    val rc = LiveRunner.serve(captain, args) {
        prompt = "fib> "
    }
    exitProcess(rc)
}
