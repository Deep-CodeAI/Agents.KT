package agents_engine.model

/**
 * #2749 — recoverable budget breach carrying the captured
 * [agents_engine.core.SessionSnapshot]. Subclass of [BudgetExceededException]
 * so existing `catch (BudgetExceededException)` blocks still fire; consumers
 * that want the snapshot path use `catch (BudgetCheckpointException)` or
 * pattern-match on the type.
 *
 * Thrown from the agentic loop when an `onBudgetExceeded` handler returns
 * [BudgetDecision.Checkpoint] AND an `onTurnCheckpoint` is registered on
 * the current invocation. Without the hook, the runtime falls back to
 * regular [BudgetExceededException] (Stop semantics).
 *
 * @property snapshot the captured loop state at the turn boundary
 *   immediately before the would-be cap breach. Resume with
 *   `agent.invokeSuspendResuming(input, resumeFrom = snapshot)`.
 * @property currentLimit the cap that the breach would have hit (passed
 *   verbatim from the breach site so the caller can compute a sensible
 *   raise — `newLimit = currentLimit * 2` or similar).
 */
class BudgetCheckpointException(
    val snapshot: agents_engine.core.SessionSnapshot,
    reason: BudgetReason,
    val currentLimit: Int,
) : BudgetExceededException(
    "Agent run checkpointed at $reason cap ($currentLimit). " +
        "Resume via invokeSuspendResuming(input, resumeFrom = exception.snapshot).",
    reason,
)
