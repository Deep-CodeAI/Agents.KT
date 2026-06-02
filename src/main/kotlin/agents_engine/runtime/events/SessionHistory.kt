package agents_engine.runtime.events

/**
 * #2485 (under Koog regression epic #2474) — ergonomic, stable views over
 * an [AgentSession]'s collected event log. Thin wrapper around a
 * `List<AgentEvent<*>>` — no new state, deterministic ordering preserved
 * from the source flow.
 *
 * Why a wrapper class rather than extension functions on the list itself:
 * better IDE discovery (`history.toolCalls()`) and a stable home for
 * additional accessors as they're added.
 *
 * NOT covered in v1: a `userMessages()` accessor. The agent input is
 * passed to `agent.session(input)` directly and is not surfaced as an
 * event in the current event hierarchy; adding it requires a new
 * `AgentEvent.UserMessage` and is out of scope for this slice.
 */
class SessionHistory(val events: List<AgentEvent<*>>) {

    /**
     * Successful tool invocations in the order they completed.
     * `isError == true` calls are included so a consumer can decide
     * whether to filter them — use [toolResults] with `excludeErrors` if
     * only successful results are wanted.
     */
    fun toolCalls(): List<ToolCallRecord> =
        events.filterIsInstance<AgentEvent.ToolCallFinished>()
            .map { ToolCallRecord(it.callId, it.toolName, it.arguments) }

    /**
     * Outcomes of tool invocations in completion order. Pairs the same
     * [AgentEvent.ToolCallFinished] events with their executor return
     * value (or error message). Use [excludeErrors] to drop calls that
     * surfaced as errors.
     */
    fun toolResults(excludeErrors: Boolean = false): List<ToolResultRecord> =
        events.filterIsInstance<AgentEvent.ToolCallFinished>()
            .filter { !excludeErrors || !it.isError }
            .map { ToolResultRecord(it.callId, it.toolName, it.result, it.isError) }

    /**
     * Assistant text output, one entry per model turn. Assembled by
     * grouping [AgentEvent.Token] events by their containing
     * `ModelTurnCompleted`. A turn with no tokens (pure tool-call turn)
     * produces an empty string entry; filter those out with `.filter { it.isNotEmpty() }`
     * if undesired.
     */
    fun assistantMessages(): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        for (event in events) {
            when (event) {
                is AgentEvent.Token -> current.append(event.text)
                is AgentEvent.ModelTurnCompleted -> {
                    result.add(current.toString())
                    current.clear()
                }
                else -> Unit
            }
        }
        // If the stream ended mid-turn (rare — usually ModelTurnCompleted
        // is the terminal model event), surface whatever was buffered.
        if (current.isNotEmpty()) result.add(current.toString())
        return result
    }

    /**
     * The terminal typed output of the session, or null if it didn't
     * complete successfully (e.g., the session ended with [AgentEvent.Failed]).
     * Generic returned as `Any?` because the [AgentEvent.Completed] type
     * parameter is erased when packed into a heterogeneous event list.
     */
    fun completedOutput(): Any? =
        events.filterIsInstance<AgentEvent.Completed<*>>().singleOrNull()?.output

    /** The terminal failure event, or null if the session completed normally. */
    fun failed(): AgentEvent.Failed? =
        events.filterIsInstance<AgentEvent.Failed>().singleOrNull()

    /** Skill executions that started during this session, in start order. */
    fun skillsStarted(): List<String> =
        events.filterIsInstance<AgentEvent.SkillStarted>().map { it.skillName }
}
