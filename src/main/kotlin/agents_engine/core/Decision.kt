package agents_engine.core

import agents_engine.model.LlmMessage

typealias ChatMessage = LlmMessage

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

class InterceptorDeniedException(message: String) : RuntimeException(message)

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
