package agents_engine.langfuse

internal interface LangfuseIngestionSink {
    fun send(batch: List<LangfuseIngestionEvent>)
}
