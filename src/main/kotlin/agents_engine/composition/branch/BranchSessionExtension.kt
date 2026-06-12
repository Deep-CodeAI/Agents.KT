package agents_engine.composition.branch

import agents_engine.core.Agent
import agents_engine.runtime.events.AgentSession
import agents_engine.runtime.events.agentSessionScope
import agents_engine.runtime.events.runAgentInSession

/**
 * `agents_engine/composition/branch/BranchSessionExtension.kt` — adds the
 * `branch.session(input)` extension. Source agent runs first, streaming
 * events with `agentId=source.name`. The matched route's agent (or
 * pipeline) then runs, streaming its own events. Terminal `Completed`
 * carries the routed agent's name as `agentId`. Routes built outside
 * `BranchBuilder` lack `sessionExecutor` → fall back to non-streaming
 * `executor` but still surface terminal `Completed`/`Failed` (#1748). The
 * channel / scope / context / terminal-event lifecycle lives in the shared
 * [agentSessionScope] (#2797).
 * See `src/main/resources/internals-agent/composition/branch/BranchSessionExtension.md`
 * (#1837 / #1866).
 */

/**
 * #1748 — start a streaming session against [this] branch.
 *
 * The source agent runs first to produce the routing value; its events
 * stream with `agentId=source.name`. The matched route's agent (or
 * pipeline) then runs; its events stream with their own `agentId`s.
 * Terminal `Completed` carries the routed agent's name as `agentId` —
 * that's the agent whose output is the Branch's output.
 *
 * Failure handling: if either the source or the routed agent throws,
 * the terminal event is `Failed` with the original cause; `await()`
 * rethrows. Routes constructed outside `BranchBuilder` (no
 * `sessionExecutor`) fall back to the regular executor — events from
 * the routed agent won't stream, but the route still executes and
 * the terminal `Completed`/`Failed` fires correctly.
 */
fun <IN, OUT> Branch<IN, OUT>.session(input: IN): AgentSession<OUT> {
    val branch = this
    // Terminal id starts as the source and becomes the routed agent once matched. The Failed path
    // reads whatever it had resolved to at the throw point — hence a supplier over a captured var.
    var terminalAgentId = branch.source.name
    return agentSessionScope({ terminalAgentId }) { emit ->
        // Source agent streams first.
        @Suppress("UNCHECKED_CAST")
        val sourceOut = runAgentInSession(branch.source as Agent<IN, Any?>, input, emit).first

        // Pick the matching route and run it.
        val route = branch.matchRoute(sourceOut)
            ?: error(
                "No branch route matched for ${sourceOut?.let { it::class.simpleName } ?: "null"} " +
                    "and no onElse clause was declared."
            )
        // Terminal Completed gets the routed agent's name — the agent whose output became the Branch's
        // typed output. Falls back to source.name when the route was built outside BranchBuilder
        // (no recorded routedAgentName).
        terminalAgentId = route.routedAgentName ?: branch.source.name

        branch.fireHandoffIfNeeded(route, sourceOut)
        route.sessionExecutor?.invoke(sourceOut, emit) ?: route.executor(sourceOut)
    }
}
