package agents_engine.web

interface RenderBackend {
    val name: String
    val capabilities: Set<WebCapability>

    fun fetch(request: WebRequest): WebResponse
}
