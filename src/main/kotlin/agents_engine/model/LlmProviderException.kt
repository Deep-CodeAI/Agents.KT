package agents_engine.model

/**
 * `agents_engine/model/LlmProviderException.kt` — the boundary error
 * type for LLM-provider protocol failures (auth, capability mismatch,
 * model-not-found, malformed request). See
 * `src/main/resources/internals-agent/model/LlmProviderException.md`
 * for the adjunct surfaced to IDE-side LLM tools (#1837 / #1849).
 */

/**
 * Thrown when an LLM provider rejects the request at the protocol boundary —
 * not when the model returns bad output. Distinguished from
 * `IllegalStateException` (which downstream `transformOutput` parsers throw)
 * so callers can route provider failures separately from output-parsing failures.
 *
 * Examples:
 * - Capability mismatch: model doesn't support tools / vision / etc.
 * - Model not found.
 * - Malformed request body.
 * - Authentication / authorization failures.
 *
 * See #702.
 */
class LlmProviderException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
