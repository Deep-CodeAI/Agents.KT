package agents_engine.runtime

import agents_engine.core.Agent

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
