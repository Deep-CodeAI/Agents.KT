package agents_engine.nlweb

import agents_engine.model.NlWebSearchResult

/**
 * The retrieval seam an [NlWebServer] calls for each `/ask` (#4542): answer an [NlWebAskRequest]
 * with the ranked schema.org [NlWebSearchResult]. The server is pure transport — back this with the
 * RAG `EmbeddingStore` seam (`:agents-kt-rag`), an [agents_engine.core.Agent], or any retrieval you
 * like. Keep it fast and side-effect-light; it runs on the HTTP dispatch thread.
 */
fun interface NlWebAskHandler {
    fun ask(request: NlWebAskRequest): NlWebSearchResult
}
