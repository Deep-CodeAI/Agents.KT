package agents_engine.model

import agents_engine.core.Agent
import agents_engine.core.AgentRuntimeContext
import agents_engine.core.HumanDecision
import agents_engine.core.SessionSnapshot
import agents_engine.core.SnapshotManifestMismatchException
import agents_engine.core.withAgentRuntimeContext
import agents_engine.generation.toLlmInput

/**
 * #3376 batch 4 — the resume/HITL restore step extracted from `executeAgentic`'s inline
 * `if (resumeFrom != null)` block. Seeds [messages] + namespaced memory from a prior [SessionSnapshot]
 * and, when the snapshot carries a pending HITL interrupt, synthesises the human's reply as the
 * interrupted tool's result message. Fails closed on a manifest-hash mismatch (#2754) unless the
 * caller opts out. Mutates the [messages] list in place.
 */
internal suspend fun restoreFromSnapshot(
    agent: Agent<*, *>,
    resumeFrom: SessionSnapshot,
    allowManifestMismatch: Boolean,
    resumeWith: Any?,
    runtimeContext: AgentRuntimeContext,
    messages: MutableList<LlmMessage>,
) {
    // #2754 — fail closed on manifestHash mismatch unless the caller explicitly opts out. Null
    // snapshot.manifestHash means the snapshot predates the guard (or the originating agent had no
    // manifest); allow.
    val snapHash = resumeFrom.manifestHash
    if (!allowManifestMismatch && snapHash != null && snapHash != agent.manifestHash) {
        throw SnapshotManifestMismatchException(
            expected = snapHash,
            actual = agent.manifestHash,
        )
    }
    messages.addAll(resumeFrom.messages)
    // #2755 — only restore THIS agent's namespaced slot, not the whole bank. The wipe-all
    // `restore(Map)` was destructive in the shared-workspace topology (one bank, many agents):
    // resuming session A would erase session B's slot. Snapshot.memory carries `{agentName: value}`
    // for the resuming agent only.
    agent.memoryBank?.let { bank ->
        val mine = resumeFrom.memory[agent.name]
        bank.restoreForAgent(agent.name, mine)
    }
    // #2488 — HITL interrupt resume. If the snapshot carries a pending interrupt call id, synthesise
    // the tool result message from `resumeWith` and append it so the next model turn sees it as the
    // result of the call it issued before the pause. v1 constraint: single-tool-per-interrupting-turn
    // (see Interrupt.kt).
    val pendingCallId = resumeFrom.pendingInterruptCallId
    if (pendingCallId != null) {
        require(resumeWith != null) {
            "Snapshot has pendingInterruptCallId=$pendingCallId but resumeWith was not provided. " +
                "Pass resumeWith = <the human's reply> to invokeSuspendResuming / executeAgentic."
        }
        // #2489 — if resumeWith is a HumanDecision, emit the audit event before synthesising the tool
        // result. Renders the decision verbatim into the LLM context via toLlmInput.
        if (resumeWith is HumanDecision) {
            val (decisionName, hasPayload) = when (resumeWith) {
                HumanDecision.Approved -> "Approved" to false
                HumanDecision.Rejected -> "Rejected" to false
                is HumanDecision.Edited -> "Edited" to (resumeWith.payload != null)
                is HumanDecision.Responded -> "Responded" to (resumeWith.payload != null)
            }
            withAgentRuntimeContext(runtimeContext) {
                agent.approvalDecidedListener?.invoke(decisionName, hasPayload)
            }
        }
        // toLlmInput renders @Generable typed replies as JSON; strings stay strings. The OpenAI
        // adapter pairs tool results to preceding assistant tool_calls positionally, so the call_id
        // only needs to live on the snapshot — not on LlmMessage itself.
        val synthesised = LlmMessage(
            role = "tool",
            content = toLlmInput(resumeWith),
        )
        messages.add(synthesised)
    }
}
