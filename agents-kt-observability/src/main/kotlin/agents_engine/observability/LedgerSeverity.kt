package agents_engine.observability

/**
 * #2905 — how alarming a ledger row is, for triage when reading back an audit trail.
 * Orthogonal to [LedgerDecision]: severity is a fixed function of the decision (see
 * [LedgerDecision.severity]), so it is *derived*, never a separate persisted column —
 * the Merkle hash schema is unchanged and old ledgers still verify.
 *
 * - [INFO] — normal, authorized activity (an approved tool call).
 * - [WARN] — contained misbehaviour or operational failure: the model overreached
 *   (hallucinated tool), hit a resource ceiling, or a call failed — recoverable.
 * - [CRITICAL] — a guardrail actively blocked a forbidden action (a policy/interceptor
 *   denial). This is the strongest "an agent tried to do something it shouldn't" signal,
 *   which is exactly what the audit log exists to answer.
 */
enum class LedgerSeverity { INFO, WARN, CRITICAL }
