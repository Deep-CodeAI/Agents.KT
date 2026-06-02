package agents_engine.model

import kotlin.time.Duration

/**
 * `agents_engine/model/CacheHint.kt` — the neutral, vendor-agnostic
 * prompt-caching hint attached to `LlmMessage`s by the agentic loop
 * (#2656, part of the #2655 epic).
 *
 * Adapters consume hints and translate them to provider-specific
 * mechanisms (Anthropic `cache_control` breakpoints, Gemini explicit
 * cached-content handles, OpenAI / DeepSeek automatic prefix caching,
 * Ollama / vLLM engine APC). A hint a given provider cannot honour
 * silently no-ops — caching is a latency / cost optimisation, never a
 * correctness condition.
 *
 * No provider cache types appear in this public API.
 *
 * See `src/main/resources/internals-agent/model/CacheHint.md` for the
 * adjunct surfaced to IDE-side LLM tools via `agents-kt-internals`.
 */

/**
 * Vendor-neutral cache hint attached to an `LlmMessage`. The presence of a
 * hint marks the message as the end of a cacheable group; the adapter
 * decides what that means on the wire.
 *
 * @property segment which logical part of the prompt this hint covers.
 * @property ttl desired cache lifetime. Null = let the adapter use its
 *   provider's default (e.g. Anthropic's ~5 min). Adapters whose providers
 *   ignore explicit TTL silently no-op this field.
 * @property breakpoint when true (the default), the adapter should place an
 *   explicit cache marker at this point (Anthropic-style breakpoint, Gemini
 *   explicit handle boundary). When false, the segment is tagged as
 *   conceptually cacheable but no explicit marker is requested — useful for
 *   automatic-prefix-caching providers (OpenAI / DeepSeek / Ollama) where
 *   markers are not used but the tag still feeds routing-key choice and
 *   observability.
 */
data class CacheHint(
    val segment: CacheSegment,
    val ttl: Duration? = null,
    val breakpoint: Boolean = true,
)
