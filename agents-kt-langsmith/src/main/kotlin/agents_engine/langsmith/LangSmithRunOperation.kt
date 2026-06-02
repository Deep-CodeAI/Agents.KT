package agents_engine.langsmith

internal sealed interface LangSmithRunOperation {
    data class Create(val run: Map<String, Any?>) : LangSmithRunOperation
    data class Update(val runId: String, val patch: Map<String, Any?>) : LangSmithRunOperation
}
