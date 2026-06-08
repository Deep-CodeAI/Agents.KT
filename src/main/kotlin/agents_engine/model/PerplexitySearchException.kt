package agents_engine.model

/** Raised when Perplexity returns an error envelope or a non-2xx status (#3676). */
class PerplexitySearchException(message: String) : RuntimeException(message)
