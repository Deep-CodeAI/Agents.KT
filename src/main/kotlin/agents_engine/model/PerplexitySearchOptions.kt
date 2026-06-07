package agents_engine.model

/**
 * Options controlling one Perplexity search request (#3676 + #3677). All
 * controls beyond [model] default to "unset" → omitted from the request, so a
 * bare `PerplexitySearchOptions()` produces the same plain `sonar` web search
 * as before (additive, backward-compatible). Build ergonomically with
 * [perplexitySearchOptions].
 *
 * @property model sonar variant: `sonar` / `sonar-pro` / `sonar-reasoning-pro` / `sonar-deep-research`.
 * @property mode `search_mode` — web / academic / sec.
 * @property recency `search_recency_filter` — hour / day / week / month / year.
 * @property domainsAllow `search_domain_filter` allow-list (bare domains).
 * @property domainsDeny `search_domain_filter` deny-list (serialized with a `-` prefix).
 * @property contextSize `web_search_options.search_context_size` — low / medium / high.
 * @property reasoningEffort `reasoning_effort` for reasoning / deep-research models
 *   (the API also accepts `minimal`, not modeled by the shared [ReasoningEffort] enum).
 * @property jsonSchema native `response_format` json_schema — constrains the answer
 *   to a strict schema (set via [PerplexitySearchOptionsBuilder.structuredOutput]).
 */
data class PerplexitySearchOptions(
    val model: String = "sonar",
    val mode: SearchMode? = null,
    val recency: SearchRecency? = null,
    val domainsAllow: List<String> = emptyList(),
    val domainsDeny: List<String> = emptyList(),
    val contextSize: SearchContextSize? = null,
    val reasoningEffort: ReasoningEffort? = null,
    val jsonSchema: JsonSchema? = null,
)
