package agents_engine.core

import kotlin.time.Duration

/**
 * `agents_engine/core/HumanApproval.kt` — first-class human approval gate
 * (#2489), built on the interrupt primitive ([interrupt] in
 * [agents_engine.core.Interrupt]). Promotes the typed-approval pattern
 * sketched by the #1918 demo to a runtime feature.
 *
 * The shape:
 *
 * ```kotlin
 * tool("approve_deploy") { args ->
 *     humanApproval {
 *         title = "Deploy to production?"
 *         body = deploymentPlan        // typed
 *         timeout = 30.minutes
 *         defaultOnTimeout = HumanDecision.Rejected
 *     }
 *     // ↑ never returns — throws AgentInterruptException carrying the
 *     // ApprovalRequest. The caller asks the human, then resumes via
 *     // invokeSuspendResuming(..., resumeWith = <HumanDecision>).
 * }
 * ```
 *
 * **Sealed [HumanDecision].** Not a boolean. The four variants —
 * `Approved`, `Rejected`, `Edited(payload)`, `Responded(payload)` —
 * capture the four real-world outcomes for a typed approval. Edited
 * carries the modified plan; Responded carries a free-form reply
 * (e.g. "ask the user this clarifying question first").
 *
 * **Audit events.** [Agent.observe] subscribers see two new
 * [PipelineEvent] variants: [PipelineEvent.ApprovalRequested] (emitted
 * by the agentic loop when the interrupt payload is an [ApprovalRequest])
 * and [PipelineEvent.ApprovalDecided] (emitted when the resume path
 * synthesises a tool result from a [HumanDecision] `resumeWith`).
 * Field-only — no payload bodies in the audit row, since payloads can
 * be high-volume or PII-sensitive. Bridges (OTel / LangSmith /
 * Langfuse) and the JSONL audit exporter pick them up via the usual
 * `observe { }` seam.
 *
 * **Timeout.** [ApprovalRequest.timeout] and
 * [ApprovalRequest.defaultOnTimeout] are advisory — the runtime can't
 * honor them inside the suspension because the human reply happens
 * BETWEEN `catch (AgentInterruptException)` and the next call to
 * `invokeSuspendResuming(...)`. They're carried on the request so the
 * caller has a contract for how to behave on expiry: when the
 * configured timeout elapses without a reply, the caller should resume
 * with `resumeWith = request.defaultOnTimeout`.
 *
 * Pairs with #2487 (HITL epic) and #2488 (interrupt primitive).
 */

/**
 * A typed request for human input. Surfaced as the payload of
 * [AgentInterruptException] when `humanApproval { }` fires.
 *
 * @property title short prompt rendered to the human (e.g. "Deploy to
 *   production?").
 * @property body optional context — typed (`@Generable` or anything
 *   `toLlmInput`-renderable) or null. Typically the plan or artefact
 *   the human is reviewing.
 * @property timeout advisory wall-clock cap on how long the runtime
 *   would wait if it were managing the timer (which it isn't — see
 *   class header). Null = no advisory.
 * @property defaultOnTimeout the decision the caller should synthesise
 *   if [timeout] expires without a human reply. Defaults to
 *   [HumanDecision.Rejected] — fail-closed for sensitive actions is
 *   the right default for a regulated runtime.
 */
data class ApprovalRequest(
    val title: String,
    val body: Any? = null,
    val timeout: Duration? = null,
    val defaultOnTimeout: HumanDecision = HumanDecision.Rejected,
)

/**
 * The sealed result of a human approval request. Caller passes one of
 * these as `resumeWith` to `invokeSuspendResuming(...)`:
 *
 * - [Approved] — proceed.
 * - [Rejected] — refuse. Sensitive actions should fail-closed.
 * - [Edited] — the human modified the plan; `payload` carries the new
 *   plan (typically the same type as the original `body`).
 * - [Responded] — the human gave a free-form reply (e.g. "first ask
 *   the user for clarification on X"); `payload` is the reply.
 */
sealed interface HumanDecision {
    object Approved : HumanDecision
    object Rejected : HumanDecision
    data class Edited(val payload: Any?) : HumanDecision
    data class Responded(val payload: Any?) : HumanDecision
}

/**
 * DSL builder for [humanApproval].
 */
class ApprovalBuilder {
    var title: String = ""
    var body: Any? = null
    var timeout: Duration? = null
    var defaultOnTimeout: HumanDecision = HumanDecision.Rejected

    internal fun build(): ApprovalRequest {
        require(title.isNotBlank()) { "humanApproval { } requires a non-blank title." }
        return ApprovalRequest(
            title = title,
            body = body,
            timeout = timeout,
            defaultOnTimeout = defaultOnTimeout,
        )
    }
}

/**
 * Pause the agentic loop for human approval. Throws — never returns.
 *
 * Equivalent to constructing an [ApprovalRequest] and calling
 * [interrupt] with it. The agentic loop recognises the payload as an
 * [ApprovalRequest] and emits [PipelineEvent.ApprovalRequested] for
 * audit consumers before throwing.
 *
 * The caller catches [AgentInterruptException], inspects `payload as
 * ApprovalRequest`, asks the human, then resumes via:
 *
 * ```kotlin
 * agent.invokeSuspendResuming(
 *     input = originalInput,
 *     resumeFrom = exception.snapshot,
 *     resumeWith = HumanDecision.Approved,  // or .Rejected / .Edited(...) / .Responded(...)
 * )
 * ```
 *
 * The model sees the [HumanDecision] rendered as JSON (via
 * [agents_engine.generation.toLlmInput]) on the synthesised tool
 * result message. From its perspective the round-trip is invisible.
 */
fun humanApproval(block: ApprovalBuilder.() -> Unit): Nothing {
    val request = ApprovalBuilder().apply(block).build()
    interrupt(payload = request)
}
