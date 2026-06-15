package agents_engine.model

/**
 * NLWeb `/ask` query mode (#4541). `LIST` returns the ranked schema.org matches;
 * `SUMMARIZE` adds an LLM summary of the list; `GENERATE` is full RAG — the
 * endpoint composes a direct answer from the retrieved items. Sent lowercase on
 * the wire. Defaults to `LIST`.
 */
enum class NlWebMode { LIST, SUMMARIZE, GENERATE }
