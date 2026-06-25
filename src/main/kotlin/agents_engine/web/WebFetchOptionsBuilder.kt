package agents_engine.web

class WebFetchOptionsBuilder {
    var maxBytes: Long = WebFetchOptions.DEFAULT_MAX_BYTES
    var respectRobotsTxt: Boolean = true

    private val allowedHosts = linkedSetOf<String>()
    private val allowedContentTypes = linkedSetOf<String>().apply {
        addAll(WebFetchOptions.DEFAULT_CONTENT_TYPES)
    }
    private var fixedUrl: String? = null
    private var captureScreenshot: Boolean = false

    fun allowHost(host: String) {
        allowedHosts += normalizeHost(host)
    }

    fun fixedUrl(url: String) {
        fixedUrl = url.trim()
    }

    fun clearContentTypes() {
        allowedContentTypes.clear()
    }

    fun allowContentTypes(vararg contentTypes: String) {
        contentTypes.forEach { allowedContentTypes += normalizeContentType(it) }
    }

    fun captureScreenshot() {
        captureScreenshot = true
    }

    fun build(): WebFetchOptions =
        WebFetchOptions(
            allowedHosts = allowedHosts.toList(),
            fixedUrl = fixedUrl,
            maxBytes = maxBytes,
            allowedContentTypes = allowedContentTypes.toSet(),
            respectRobotsTxt = respectRobotsTxt,
            captureScreenshot = captureScreenshot,
        )
}

fun webFetchOptions(block: WebFetchOptionsBuilder.() -> Unit): WebFetchOptions =
    WebFetchOptionsBuilder().apply(block).build()
