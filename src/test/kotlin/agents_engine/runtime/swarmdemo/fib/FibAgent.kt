package agents_engine.runtime.swarmdemo.fib

import agents_engine.core.Agent
import agents_engine.core.agent
import agents_engine.runtime.AgentProvider
import agents_engine.runtime.LiveRunner
import agents_engine.runtime.Swarm
import agents_engine.runtime.absorb
import kotlin.system.exitProcess

// Captain JAR for the swarm demo (#984). Lives in build/tmp/jars_swarm_demo/fib.jar.
// Self-contained: own copies of small helpers so the JAR doesn't need to
// pull in shared "common" classes from anywhere else.

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

internal fun fib(n: Int): Long {
    require(n >= 0) { "n must be ≥ 0" }
    var a = 0L; var b = 1L
    repeat(n) { val t = a + b; a = b; b = t }
    return a
}

fun buildFibAgent(): Agent<String, String> = agent("fib") {
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
        - `recap` — delegate to the recap sibling. Use whenever the
          user asks for a recap, summary, or your "opinion", or says
          things like "how was that", "how fun was that", "what do you
          remember", "show your memory". The recap sibling pops up its
          own Swing window; you MUST NOT paraphrase or summarise its
          response — just acknowledge it briefly. Call with the user's
          original request as the `query` argument.
        - `exit` — delegate to the exit sibling. Use whenever the user
          says exit / quit / leave / close / bye / goodbye / "I'm done".
          Call it with the user's original request as the `query`
          argument. NEVER reply with "session closed" in plain text;
          ALWAYS call the `exit` tool.

        After a tool returns, render the result in 1–2 short sentences.
    """.trimIndent())
    model { ollama(MODEL); host = HOST; port = PORT; temperature = 0.0 }
    lateinit var fibonacci: agents_engine.model.Tool<Map<String, Any?>, Any?>
    tools {
        fibonacci = tool("fibonacci", "Compute the nth Fibonacci number. Argument: n (integer ≥ 0).") { args ->
            val n = readInt(args, "n")
            fib(n).toString()
        }
    }
    skills {
        skill<String, String>("compute", "Run a math calculation, possibly via tools") {
            tools(fibonacci)
        }
    }
    onToolUse { name, args, result -> traceTool("fib", name, args, result) }
}

class FibProvider : AgentProvider {
    override fun build(): Agent<*, *> = buildFibAgent()
}

// Captain main — packaged into fib.jar. The swarmDemo Gradle task launches
// this with classpath = framework + every sibling JAR (fib + factor + exit
// + recap); the classpath does NOT include the test source classes, so
// ServiceLoader finds providers only from the JARs on disk.
fun main(args: Array<String>) {
    val members = Swarm.discover()
    val me = members.singleOrNull { it.name == "fib" }
        ?: error("captain 'fib' not found among discovered providers: ${members.map { it.name }}")
    val siblings = members.filter { it.name != me.name }

    println()
    println("============================================================")
    println("Swarm demo (#984) — captain: ${me.name}")
    println("Discovered ${siblings.size} siblings: ${siblings.joinToString { it.name }}")
    println("Each sibling carries its own personality (prompt + tools);")
    println("the captain absorbed them as tools and dispatches via its LLM.")
    println()
    println("Try:")
    println("  fib> what's fib(10)?")
    println("  fib> factor 84")
    println("  fib> how fun was that?         (pops up the recap window)")
    println("  fib> bye, please exit the app")
    println("============================================================")
    println()

    siblings.forEach { sibling -> me.absorb(sibling) }

    @Suppress("UNCHECKED_CAST")
    val captain = me as Agent<String, String>
    val rc = LiveRunner.serve(captain, args) {
        prompt = "fib> "
    }
    exitProcess(rc)
}
