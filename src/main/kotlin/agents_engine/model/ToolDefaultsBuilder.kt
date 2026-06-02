package agents_engine.model

class ToolDefaultsBuilder {
    internal var errorHandler: ToolErrorHandler? = null

    fun onError(block: OnErrorBuilder.() -> Unit) {
        val builder = OnErrorBuilder()
        builder.block()
        errorHandler = builder.build()
    }
}
