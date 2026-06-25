package agents_engine.web

class StaticRenderBackend(
    private val fetcher: (WebRequest) -> WebResponse,
) : RenderBackend {
    override val name: String = "Static"
    override val capabilities: Set<WebCapability> = emptySet()

    override fun fetch(request: WebRequest): WebResponse = fetcher(request)
}

fun staticBackend(fetcher: (WebRequest) -> WebResponse): StaticRenderBackend =
    StaticRenderBackend(fetcher)
