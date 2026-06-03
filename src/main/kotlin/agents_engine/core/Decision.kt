package agents_engine.core

import agents_engine.model.LlmMessage
import java.util.logging.Level
import java.util.logging.Logger

typealias ChatMessage = LlmMessage

private val DISPATCH_LOGGER: Logger = Logger.getLogger("agents_engine.core.dispatch")

/**
 * #2793 — the single swallow-and-log policy for observability dispatch, co-located with the shared
 * interceptor primitive [runDecisionChain]. A listener / observer failure is logged at WARNING and
 * swallowed so user instrumentation (telemetry, audit hooks, decision observers) can never break an
 * agent run. [what] names the channel for the log line, e.g. `"onTokenUsage listener"`. Replaces the
 * three near-identical try/catch blocks copy-pasted across the Agent god class and interceptor wiring.
 */
internal inline fun dispatchSafely(what: String, block: () -> Unit) {
    try {
        block()
    } catch (t: Throwable) {
        DISPATCH_LOGGER.log(Level.WARNING, "$what failed; swallowing", t)
    }
}

/**
 * `agents_engine/core/Decision.kt` — before-interceptor return type (#1907).
 * Interceptors can proceed, replace the inspected value, deny the operation,
 * or short-circuit with a synthetic result.
 */
sealed interface Decision<out T> {
    object Proceed : Decision<Nothing>
    data class ProceedWith<T>(val replacement: T) : Decision<T>
    data class Deny(val reason: String) : Decision<Nothing>
    data class Substitute<out R>(val result: R) : Decision<Nothing>
}

internal fun <T> runDecisionChain(
    initial: T,
    interceptors: List<(T) -> Decision<T>>,
): Decision<T> {
    var current = initial
    var effective: Decision<T> = Decision.Proceed

    interceptors.forEach { interceptor ->
        val decision = try {
            interceptor(current)
        } catch (t: Throwable) {
            Decision.Deny(t.message ?: t.toString())
        }

        if (effective is Decision.Proceed) {
            effective = decision
            if (decision is Decision.ProceedWith<*>) {
                @Suppress("UNCHECKED_CAST")
                current = decision.replacement as T
            }
        }
    }

    return effective
}
