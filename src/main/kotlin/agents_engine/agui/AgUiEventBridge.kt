package agents_engine.agui

import agents_engine.mcp.McpJson
import agents_engine.runtime.events.AgentEvent
import java.util.UUID

/**
 * `agents_engine/agui/AgUiEventBridge.kt` — #4523 (PRD §12.7). Translates this runtime's typed
 * [AgentEvent] stream into [AG-UI](https://github.com/ag-ui-protocol/ag-ui) protocol events (the JSON
 * payloads an AG-UI SSE stream carries). AG-UI is the agent↔frontend layer (MCP = agent↔tools, A2A =
 * agent↔agent, AG-UI = agent↔user); the event surface maps ~1:1 onto [AgentEvent], so this is the bridge
 * [AgUiServer] runs each run through.
 *
 * **Envelope + ordering** (the AG-UI contract): every run is `RUN_STARTED` … `RUN_FINISHED` (or `RUN_ERROR`).
 * Text is `TEXT_MESSAGE_START` → `TEXT_MESSAGE_CONTENT`* → `TEXT_MESSAGE_END`; tool calls are
 * `TOOL_CALL_START` → `TOOL_CALL_ARGS`* → `TOOL_CALL_END`. This bridge holds the small state machine that
 * opens a text message on the first [AgentEvent.Token] and closes it before any tool call, step boundary,
 * or run finish — so the emitted stream always satisfies that ordering.
 *
 * Stateful and single-run: construct one per run. Not thread-safe; [AgUiServer] drives it from one collector.
 *
 * v1 surfaces the lifecycle/text/tool/step families. STATE events (shared agent↔UI state — no runtime model
 * yet), REASONING / THINKING events ([AgentEvent.Reasoning]), and MESSAGES_SNAPSHOT are documented
 * follow-ups; the corresponding [AgentEvent]s are simply not surfaced yet.
 */
internal class AgUiEventBridge(private val threadId: String, private val runId: String) {
    private var textMessageId: String? = null
    private var anyText = false

    /** The opening `RUN_STARTED`. Emit once before collecting the session. */
    fun runStarted(): String = event("RUN_STARTED", "threadId" to threadId, "runId" to runId)

    /** Map one [AgentEvent] to zero or more AG-UI event payloads (in order). */
    fun onEvent(e: AgentEvent<*>): List<String> = when (e) {
        is AgentEvent.Token -> buildList {
            if (textMessageId == null) {
                val id = newId().also { textMessageId = it; anyText = true }
                add(event("TEXT_MESSAGE_START", "messageId" to id, "role" to "assistant"))
            }
            add(event("TEXT_MESSAGE_CONTENT", "messageId" to textMessageId!!, "delta" to e.text))
        }

        is AgentEvent.SkillStarted -> listOf(event("STEP_STARTED", "stepName" to e.skillName))
        is AgentEvent.SkillCompleted -> closeText() + event("STEP_FINISHED", "stepName" to e.skillName)

        is AgentEvent.ToolCallStarted ->
            closeText() + event("TOOL_CALL_START", "toolCallId" to e.callId, "toolCallName" to e.toolName)
        is AgentEvent.ToolCallArgumentsDelta ->
            listOf(event("TOOL_CALL_ARGS", "toolCallId" to e.callId, "delta" to e.deltaJson))
        is AgentEvent.ToolCallFinished ->
            listOf(event("TOOL_CALL_END", "toolCallId" to e.callId))

        is AgentEvent.Completed<*> -> finish(e.output)
        is AgentEvent.Failed -> closeText() + runError(e.cause.message ?: e.cause.toString())

        // ModelTurnStarted/Completed, Reasoning, StageStarted/Completed — not surfaced in v1.
        else -> emptyList()
    }

    /** A standalone `RUN_ERROR` (used by [AgUiServer] as a backstop if collection throws unexpectedly). */
    fun runError(message: String): String = event("RUN_ERROR", "message" to message)

    private fun finish(output: Any?): List<String> = buildList {
        addAll(closeText())
        // No tokens streamed (e.g. a deterministic skill) — surface the final output as one message so a UI
        // always has something to render.
        if (!anyText && output != null) {
            val id = newId()
            add(event("TEXT_MESSAGE_START", "messageId" to id, "role" to "assistant"))
            add(event("TEXT_MESSAGE_CONTENT", "messageId" to id, "delta" to output.toString()))
            add(event("TEXT_MESSAGE_END", "messageId" to id))
        }
        add(event("RUN_FINISHED", "threadId" to threadId, "runId" to runId))
    }

    private fun closeText(): List<String> {
        val id = textMessageId ?: return emptyList()
        textMessageId = null
        return listOf(event("TEXT_MESSAGE_END", "messageId" to id))
    }

    private operator fun List<String>.plus(one: String): List<String> = this + listOf(one)

    private fun newId(): String = UUID.randomUUID().toString()

    private fun event(type: String, vararg fields: Pair<String, Any?>): String {
        val obj = LinkedHashMap<String, Any?>()
        obj["type"] = type
        fields.forEach { (k, v) -> obj[k] = v }
        return McpJson.encode(obj)
    }
}
