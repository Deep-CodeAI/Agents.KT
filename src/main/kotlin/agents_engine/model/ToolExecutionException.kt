package agents_engine.model

/** Wraps an unrecoverable tool-executor failure; propagated rather than repaired. */
class ToolExecutionException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
