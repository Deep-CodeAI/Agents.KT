package agents_engine.manifest

import agents_engine.core.Agent

internal data class ManifestGraph(
    val type: String,
    val agents: List<Agent<*, *>>,
    val composition: Map<String, Any?>,
    val extra: Map<String, Any?> = emptyMap(),
)
