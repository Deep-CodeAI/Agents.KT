# `agents_engine/runtime/Swarm.kt` — multi-JAR agent assembly

Each sibling agent ships as its own JAR with its own `META-INF/services/agents_engine.runtime.AgentProvider`. The captain JAR uses `Swarm.discover()` to find them, then `Agent.absorb` to wire each as a tool on itself.

## AgentProvider

```kotlin
fun interface AgentProvider {
    fun build(): Agent<*, *>
}
```

A SAM. Each sibling-agent JAR has one implementation; the JAR's `META-INF/services/agents_engine.runtime.AgentProvider` file points at the class.

## Discovery

```kotlin
val siblings: List<Agent<*, *>> = Swarm.discover()
// or with a specific classloader:
val siblings = Swarm.discover(someClassLoader)
```

Iterates every registered `AgentProvider` via `java.util.ServiceLoader`, calls `build()` once per provider, returns the resulting `Agent`s in classloader-iteration order.

## Captain composition

```kotlin
val captain = agent<X, Y>("captain") { /* ... */ }
Swarm.discover().forEach { sibling ->
    captain.absorb(sibling)            // each sibling becomes a tool on captain
}
```

`Agent.absorb(sibling)` (defined elsewhere) creates a `ToolDef` whose executor invokes the sibling. With session-aware tools (#1752), the sibling's inner events stream into the captain's session under the captain's `agentId`.

## Why JVM-only, not MCP-stdio

Two reasons documented in the issue:
1. **Full Agent surface preservation.** Prompts, skills, knowledge, memory, observability hooks, error handlers — the full `Agent<IN, OUT>` API is available. MCP-stdio would flatten the sibling to a JSON-RPC tool call.
2. **Co-located deployment.** Swarm is for "one binary, many agents." Cross-process orchestration is what MCP is for.

Trade-off: no process isolation. A sibling that throws `OutOfMemoryError` kills the whole captain. Use MCP-stdio when isolation matters.

## Demo

The repo's swarm demo bundles three siblings (each as its own JAR), discovers them with `Swarm.discover()`, and gives a captain a single absorb-call per sibling. The captain's REPL then dispatches to them via standard skill selection.

## Related files

- `Agent.kt#absorb` — wires a sibling as a tool.
- `model/ToolDef.kt#sessionExecutor` (#1752) — the session-aware executor `absorb` produces.
- `composition/wrap/Wrap.kt` — alternative composition pattern for prompt-locked siblings.
