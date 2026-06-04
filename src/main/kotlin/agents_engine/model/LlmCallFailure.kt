package agents_engine.model

/**
 * `agents_engine/model/LlmCallFailure.kt` (#3508) — an INTERNAL recognition marker that
 * [guardLlmCall] wraps around any model-call failure, so `Agent.invokeSuspendForSession` can tell
 * "the LLM call failed" apart from a tool error, a budget cap, or cancellation.
 *
 * It NEVER escapes the agent: the chokepoint unwraps to [original] for the `onError` observer, the
 * `onLLMError` recovery hook, and the rethrow — so exception observers and `assertThrows` see the
 * *real* provider / transport exception (a down server's `ConnectException`, a provider's
 * `LlmProviderException`, a script-exhausted error). Wrapping is pure recognition plumbing; it does
 * not change the identity of the error that ultimately propagates.
 */
internal class LlmCallFailure(val original: Throwable) :
    RuntimeException(original.message, original)
