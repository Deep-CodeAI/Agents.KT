package agents_engine.core

import kotlin.time.Duration

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
