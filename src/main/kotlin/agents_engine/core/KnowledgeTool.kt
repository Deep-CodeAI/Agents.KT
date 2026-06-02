package agents_engine.core

data class KnowledgeTool(
    val name: String,
    val description: String,
    val call: () -> String,
)
