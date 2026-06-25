package agents_engine.web

data class WebResponse(
    val finalUrl: String,
    val contentType: String?,
    val body: String,
    val byteSize: Long = body.encodeToByteArray().size.toLong(),
    val robotsAllowed: Boolean = true,
    val screenshot: WebScreenshot? = null,
)
