package agents_engine.core

/**
 * #3868 — result of running an agent through a [HumanGateRegistry]:
 * either it finished without pausing, or it is parked behind a
 * [PendingGate] awaiting a human decision.
 */
sealed interface GateOutcome<out OUT> {
    data class Completed<OUT>(val output: OUT) : GateOutcome<OUT>
    data class Paused<OUT>(val gate: PendingGate<OUT>) : GateOutcome<OUT>
}
