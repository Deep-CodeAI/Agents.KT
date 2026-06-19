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
 * Surfaces the lifecycle/text/tool/step families plus REASONING (live model thinking, from
 * [AgentEvent.Reasoning]). STATE events (shared agent↔UI state — no runtime model yet) and
 * MESSAGES_SNAPSHOT remain documented follow-ups; those [AgentEvent]s are simply not surfaced yet.
 *
 * **REASONING ordering** (the AG-UI contract): a thinking block is `REASONING_START` →
 * `REASONING_MESSAGE_START` → `REASONING_MESSAGE_CONTENT`* → `REASONING_MESSAGE_END` → `REASONING_END`
 * (the older `THINKING_*` names are deprecated). Reasoning precedes the answer, so the block is opened on
 * the first [AgentEvent.Reasoning] chunk and closed before any answer text, tool call, step finish, or run
 * finish — the same discipline the text state machine uses.
 */
internal class AgUiEventBridge(private val threadId: String, private val runId: String) {
    private var textMessageId: String? = null
    private var reasoningId: String? = null
    private var anyText = false

    /** The opening `RUN_STARTED`. Emit once before collecting the session. */
    fun runStarted(): String = event("RUN_STARTED", "threadId" to threadId, "runId" to runId)

    /** Map one [AgentEvent] to zero or more AG-UI event payloads (in order). */
    fun onEvent(e: AgentEvent<*>): List<String> = when (e) {
        // Reasoning precedes the answer; close any open thinking block before the first answer token.
        is AgentEvent.Token -> buildList {
            addAll(closeReasoning())
            if (textMessageId == null) {
                val id = newId().also { textMessageId = it; anyText = true }
                add(event("TEXT_MESSAGE_START", "messageId" to id, "role" to "assistant"))
            }
            add(event("TEXT_MESSAGE_CONTENT", "messageId" to textMessageId!!, "delta" to e.text))
        }

        is AgentEvent.Reasoning -> buildList {
            if (reasoningId == null) {
                val id = newId().also { reasoningId = it }
                add(event("REASONING_START", "messageId" to id))
                add(event("REASONING_MESSAGE_START", "messageId" to id, "role" to "assistant"))
            }
            add(event("REASONING_MESSAGE_CONTENT", "messageId" to reasoningId!!, "delta" to e.text))
        }

        is AgentEvent.SkillStarted -> listOf(event("STEP_STARTED", "stepName" to e.skillName))
        is AgentEvent.SkillCompleted -> closeStreams() + event("STEP_FINISHED", "stepName" to e.skillName)

        is AgentEvent.ToolCallStarted ->
            closeStreams() + event("TOOL_CALL_START", "toolCallId" to e.callId, "toolCallName" to e.toolName)
        is AgentEvent.ToolCallArgumentsDelta ->
            listOf(event("TOOL_CALL_ARGS", "toolCallId" to e.callId, "delta" to e.deltaJson))
        is AgentEvent.ToolCallFinished ->
            listOf(event("TOOL_CALL_END", "toolCallId" to e.callId))

        is AgentEvent.Completed<*> -> finish(e.output)
        is AgentEvent.Failed -> closeStreams() + runError(e.cause.message ?: e.cause.toString())

        // ModelTurnStarted/Completed, StageStarted/Completed — not surfaced in v1.
        else -> emptyList()
    }

    /** A standalone `RUN_ERROR` (used by [AgUiServer] as a backstop if collection throws unexpectedly). */
    fun runError(message: String): String = event("RUN_ERROR", "message" to message)

    private fun finish(output: Any?): List<String> = buildList {
        addAll(closeStreams())
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

    private fun closeReasoning(): List<String> {
        val id = reasoningId ?: return emptyList()
        reasoningId = null
        return listOf(
            event("REASONING_MESSAGE_END", "messageId" to id),
            event("REASONING_END", "messageId" to id),
        )
    }

    /** Close whichever streaming block is open before a boundary (tool call, step finish, run finish). At
     * most one of reasoning/text is open at a time, but closing both is order-safe and idempotent. */
    private fun closeStreams(): List<String> = closeReasoning() + closeText()

    private operator fun List<String>.plus(one: String): List<String> = this + listOf(one)

    private fun newId(): String = UUID.randomUUID().toString()

    private fun event(type: String, vararg fields: Pair<String, Any?>): String {
        val obj = LinkedHashMap<String, Any?>()
        obj["type"] = type
        fields.forEach { (k, v) -> obj[k] = v }
        return McpJson.encode(obj)
    }
}
