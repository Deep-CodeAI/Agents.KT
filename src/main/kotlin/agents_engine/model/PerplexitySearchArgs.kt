package agents_engine.model

import agents_engine.generation.Generable
import agents_engine.generation.Guide

/**
 * The single `@Generable` argument of the `perplexitySearch` tool (#3676):
 * the natural-language query to ground against live web sources.
 */
@Generable("Arguments for a web-grounded Perplexity search")
data class PerplexitySearchArgs(
    @Guide("The natural-language search query to ground against live web sources")
    val query: String,
)
