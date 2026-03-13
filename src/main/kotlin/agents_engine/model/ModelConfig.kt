package agents_engine.model

enum class ModelProvider { OLLAMA }

data class ModelConfig(
    val name: String,
    val provider: ModelProvider,
    val temperature: Double = 0.7,
    val host: String = "localhost",
    val port: Int = 11434,
    val client: ModelClient? = null,
) {
    val baseUrl: String get() = "http://$host:$port"
}

class ModelBuilder {
    var name: String = ""
    var provider: ModelProvider = ModelProvider.OLLAMA
    var temperature: Double = 0.7
    var host: String = "localhost"
    var port: Int = 11434
    var client: ModelClient? = null

    fun ollama(modelName: String) {
        name = modelName
        provider = ModelProvider.OLLAMA
    }

    internal fun build() = ModelConfig(name, provider, temperature, host, port, client)
}
