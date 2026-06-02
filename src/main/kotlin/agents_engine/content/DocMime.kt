package agents_engine.content

/** Closed mime type for [Content.Document]. */
sealed interface DocMime {
    val wireMime: String

    object Pdf : DocMime { override val wireMime: String = "application/pdf" }
    object Docx : DocMime {
        override val wireMime: String =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    }
    object Markdown : DocMime { override val wireMime: String = "text/markdown" }
    object Html : DocMime { override val wireMime: String = "text/html" }
    object PlainText : DocMime { override val wireMime: String = "text/plain" }
}
