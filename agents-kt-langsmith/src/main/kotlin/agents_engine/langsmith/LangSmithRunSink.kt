package agents_engine.langsmith

internal interface LangSmithRunSink {
    fun send(batch: List<LangSmithRunOperation>)
}
