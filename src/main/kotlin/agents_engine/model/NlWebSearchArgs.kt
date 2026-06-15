package agents_engine.model

import agents_engine.generation.Generable
import agents_engine.generation.Guide

/**
 * The single `@Generable` argument of the `nlwebSearch` tool (#4541): the
 * natural-language query to ask an [NLWeb](https://github.com/nlweb-ai/NLWeb)
 * endpoint, which answers from a website's schema.org-structured content.
 */
@Generable("Arguments for a natural-language query against an NLWeb endpoint")
data class NlWebSearchArgs(
    @Guide("The natural-language query to ask the NLWeb site")
    val query: String,
)
