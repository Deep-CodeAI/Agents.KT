package agents_engine.web

data class WebRequest(
    val url: String,
    val captureScreenshot: Boolean = false,
)
