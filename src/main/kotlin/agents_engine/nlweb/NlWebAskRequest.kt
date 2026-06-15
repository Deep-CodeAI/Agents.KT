package agents_engine.nlweb

import agents_engine.model.NlWebMode

/**
 * A parsed NLWeb `/ask` request (#4542) handed to an [NlWebAskHandler]. [query] is the
 * natural-language question; [site] is the optional namespace token the caller scoped to; [mode]
 * is `LIST` / `SUMMARIZE` / `GENERATE`. (Streaming is not modelled — the v1 server replies with a
 * single JSON blob; SSE is a follow-up.)
 */
data class NlWebAskRequest(
    val query: String,
    val site: String? = null,
    val mode: NlWebMode = NlWebMode.LIST,
)
