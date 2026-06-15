package agents_engine.model

/** Raised when an NLWeb `/ask` call returns an error envelope or a non-2xx status (#4541). */
class NlWebSearchException(message: String) : RuntimeException(message)
