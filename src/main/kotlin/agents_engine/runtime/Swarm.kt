package agents_engine.runtime

import agents_engine.core.Agent
import agents_engine.generation.Generable
import agents_engine.generation.Guide
import agents_engine.generation.constructFromMap
import agents_engine.model.ToolDef
import java.util.ServiceLoader

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

/**
 * `agents_engine/runtime/Swarm.kt` — multi-JAR agent assembly via
 * `ServiceLoader` (#984). [AgentProvider] is the SAM interface each
 * sibling-agent JAR implements; [Swarm.discover] iterates every
 * `META-INF/services/agents_engine.runtime.AgentProvider` on the
 * classpath, building one `Agent<*, *>` per provider. The captain agent
 * then calls `Agent.absorb` per sibling to wire them as session-aware
 * tools (#1752). In-JVM rather than MCP-stdio to preserve the full
 * `Agent<IN, OUT>` surface (prompts, skills, knowledge, memory,
 * observability hooks, error handlers) of every sibling. See
 * `src/main/resources/internals-agent/runtime/Swarm.md`
 * (#1837 / #1891).
 */

/**
 * Service-loadable contract for an agent shipped from a separate JAR (#984).
 *
 * Each agent JAR places `META-INF/services/agents_engine.runtime.AgentProvider`
 * pointing at a class that implements this interface. A captain agent then
 * calls [Swarm.discover] to find and build all sibling providers from the
 * classpath, and [Agent.absorb] to expose each sibling as a tool on itself.
 *
 * In-JVM (not MCP-stdio) by design — preserves the full `Agent<IN, OUT>`
 * surface (prompt, skills, knowledge, memory, observability hooks, error
 * handlers) of every sibling. Trade-off: JVM-only, no process isolation. See
 * the issue description for the rationale.
 */
fun interface AgentProvider {
    fun build(): Agent<*, *>
}

/**
 * Discovers sibling agents on the classpath via [ServiceLoader]. Every
 * registered [AgentProvider] is instantiated; its `build()` is invoked once;
 * results are returned as a list in classloader-iteration order.
 */
object Swarm {

    /** Discover via the calling thread's context classloader (the typical case). */
    fun discover(): List<Agent<*, *>> =
        discover(Thread.currentThread().contextClassLoader ?: this::class.java.classLoader)

    /**
     * Discover via [classLoader]. Used by tests that wire a custom
     * [URLClassLoader] over compiled-on-the-fly JARs, and by tooling that
     * needs explicit control of which classloader's services are seen.
     */
    fun discover(classLoader: ClassLoader): List<Agent<*, *>> =
        ServiceLoader.load(AgentProvider::class.java, classLoader)
            .iterator()
            .asSequence()
            .map { it.build() }
            .toList()
}

/**
 * Absorb a sibling agent into this captain — registers a tool named after
 * `sibling.name` whose executor delegates to `sibling.invoke(...)`. The
 * absorbed tool is auto-enabled across the captain's skills, so the
 * captain's LLM can reach the sibling without any per-skill `tools(...)`
 * declaration.
 *
 * Constraints:
 *  - sibling.name must not collide with any existing tool on the captain
 *    (or with the captain's own name — would mean absorbing self).
 *  - sibling must accept `String` input. Typed-input siblings (`Agent<X, Y>`
 *    where `X != String`) require schema-driven invocation; out of scope for
 *    v1. They throw [IllegalArgumentException] at absorb time.
 *
 * The tool input is `query: String`. The framework's existing tool-call path
 * will pass it through to `sibling.invoke(query)` and return the sibling's
 * output as the tool's result (rendered via `toString()`).
 */
fun Agent<*, *>.absorb(sibling: Agent<*, *>) {
    require(sibling.name != this.name) {
        "cannot absorb self: agent \"${this.name}\" cannot absorb itself"
    }
    require(sibling.name !in this.toolMap) {
        "agent \"${this.name}\" already has a tool named \"${sibling.name}\". " +
            "Two siblings with the same name? Pick unique agent names per JAR."
    }
    // Accept-string check. We can't reflect Kotlin generic type params from a
    // built Agent, so we sample a known String input through the sibling's
    // public type contract: every Agent<String, *> can have its first skill's
    // inType inspected. Skills carry KClass<*> for inType.
    val firstSkill = sibling.skills.values.firstOrNull()
        ?: throw IllegalArgumentException(
            "sibling \"${sibling.name}\" has no skills — nothing to absorb",
        )
    require(firstSkill.inType == String::class) {
        "sibling \"${sibling.name}\" expects ${firstSkill.inType.simpleName} input, " +
            "but absorb only supports Agent<String, *> for v1. " +
            "Consider exposing the typed agent via a String-input adapter."
    }

    @Suppress("UNCHECKED_CAST")
    val asString = sibling as Agent<String, Any?>
    val toolDescription = buildString {
        append("Delegate to the \"")
        append(sibling.name)
        append("\" agent. Skills: ")
        append(sibling.skills.values.joinToString("; ") { "${it.name} — ${it.description}" })
    }
    val tool = ToolDef(
        name = sibling.name,
        description = toolDescription,
        // #2379 — typed args for swarm delegates. The v1 absorb only
        // supports `Agent<String, *>`, so input is always {query: String}.
        argsType = SwarmDelegateArgs::class,
        executor = { args ->
            val typed = SwarmDelegateArgs::class.constructFromMap(args)
                ?: error("${sibling.name} delegate received malformed args: $args")
            asString.invoke(typed.query)?.toString() ?: "null"
        },
        // #1752 — under captain.session(input), route the sibling through
        // runAgentInSession so its inner events stream into the captain's
        // session events Flow with the sibling's own agentId.
        sessionExecutor = { args, emitter ->
            val typed = SwarmDelegateArgs::class.constructFromMap(args)
                ?: error("${sibling.name} delegate received malformed args: $args")
            agents_engine.runtime.events.runAgentInSession(asString, typed.query, emitter).first?.toString() ?: "null"
        },
    )
    registerBuiltInTool(tool)
    enableAutoTool(tool.name)
}
