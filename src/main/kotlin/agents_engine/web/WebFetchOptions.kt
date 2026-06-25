package agents_engine.web

data class WebFetchOptions(
    val allowedHosts: List<String> = emptyList(),
    val fixedUrl: String? = null,
    val maxBytes: Long = DEFAULT_MAX_BYTES,
    val allowedContentTypes: Set<String> = DEFAULT_CONTENT_TYPES,
    val respectRobotsTxt: Boolean = true,
    val captureScreenshot: Boolean = false,
) {
    companion object {
        const val DEFAULT_MAX_BYTES: Long = 1_000_000

        val DEFAULT_CONTENT_TYPES: Set<String> = setOf(
            "text/html",
            "text/plain",
            "application/xhtml+xml",
        )
    }
}
