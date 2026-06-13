package agents_engine.speechserver

/** #4506 — one parsed `multipart/form-data` part (headers + raw content bytes). */
internal class MultipartPart(
    val name: String?,
    val filename: String?,
    val contentType: String?,
    val content: ByteArray,
)

/**
 * Minimal `multipart/form-data` parser — enough for the OpenAI
 * `/v1/audio/transcriptions` shape (a `model` text part + a `file` binary part).
 * Byte-level so binary audio survives untouched; headers are ISO-8859-1.
 */
internal fun parseMultipart(body: ByteArray, boundary: String): List<MultipartPart> {
    val delimiter = "--$boundary".toByteArray(Charsets.ISO_8859_1)
    val separator = "\r\n\r\n".toByteArray(Charsets.ISO_8859_1)
    val bounds = allIndicesOf(body, delimiter)
    val parts = mutableListOf<MultipartPart>()
    for (i in 0 until bounds.size - 1) {
        var start = bounds[i] + delimiter.size
        val end = bounds[i + 1]
        if (start + 2 <= end && body[start] == CR && body[start + 1] == LF) start += 2
        val headerEnd = indexOf(body, separator, start, end)
        if (headerEnd < 0) continue
        val headerText = String(body, start, headerEnd - start, Charsets.ISO_8859_1)
        val contentStart = headerEnd + separator.size
        var contentEnd = end
        if (contentEnd - 2 >= contentStart && body[contentEnd - 2] == CR && body[contentEnd - 1] == LF) {
            contentEnd -= 2
        }
        val (name, filename) = parseDisposition(headerText)
        val content = body.copyOfRange(contentStart, contentEnd)
        parts.add(MultipartPart(name, filename, headerValue(headerText, "Content-Type"), content))
    }
    return parts
}

private const val CR: Byte = '\r'.code.toByte()
private const val LF: Byte = '\n'.code.toByte()

private fun parseDisposition(headers: String): Pair<String?, String?> {
    val line = headerValue(headers, "Content-Disposition") ?: return null to null
    val name = Regex("""name="([^"]*)"""").find(line)?.groupValues?.get(1)
    val filename = Regex("""filename="([^"]*)"""").find(line)?.groupValues?.get(1)
    return name to filename
}

private fun headerValue(headers: String, key: String): String? =
    headers.lineSequence()
        .firstOrNull { it.substringBefore(':').trim().equals(key, ignoreCase = true) }
        ?.substringAfter(':')
        ?.trim()

private fun allIndicesOf(haystack: ByteArray, needle: ByteArray): List<Int> {
    val out = mutableListOf<Int>()
    var from = 0
    while (true) {
        val at = indexOf(haystack, needle, from, haystack.size)
        if (at < 0) break
        out.add(at)
        from = at + needle.size
    }
    return out
}

private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int, to: Int): Int {
    if (needle.isEmpty()) return -1
    var i = from
    val last = to - needle.size
    while (i <= last) {
        var j = 0
        while (j < needle.size && haystack[i + j] == needle[j]) j++
        if (j == needle.size) return i
        i++
    }
    return -1
}
