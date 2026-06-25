package agents_engine.web

data class WebScreenshot(
    val mimeType: String,
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        this === other || other is WebScreenshot && mimeType == other.mimeType && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = 31 * mimeType.hashCode() + bytes.contentHashCode()
}
