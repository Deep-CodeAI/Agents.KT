package agents_engine.web

sealed interface WebContent {
    data class Page(
        val url: String,
        val contentType: String,
        val body: String,
        val screenshot: WebScreenshot? = null,
    ) : WebContent

    data class Blocked(
        val reason: BlockReason,
        val url: String,
        val detail: String? = null,
    ) : WebContent
}
