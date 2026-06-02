package agents_engine.langsmith

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

internal class LangSmithHttpRunSink(
    private val apiKey: String,
    baseUrl: String,
    private val workspaceId: String? = null,
    private val client: HttpClient = HttpClient.newHttpClient(),
) : LangSmithRunSink {

    private val endpoint = URI.create(baseUrl.trimEnd('/') + "/runs/batch")

    override fun send(batch: List<LangSmithRunOperation>) {
        if (batch.isEmpty()) return
        val creates = batch.filterIsInstance<LangSmithRunOperation.Create>().map { it.run }
        val updates = batch.filterIsInstance<LangSmithRunOperation.Update>().map { update ->
            linkedMapOf("id" to update.runId) + update.patch
        }
        val body = encodeJson(
            linkedMapOf(
                "post" to creates,
                "patch" to updates,
            ),
        )
        val requestBuilder = HttpRequest.newBuilder(endpoint)
            .header("content-type", "application/json")
            .header("x-api-key", apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(body))
        workspaceId?.let { requestBuilder.header("x-tenant-id", it) }
        val response = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw IOException("LangSmith batch ingest failed: HTTP ${response.statusCode()} ${response.body()}")
        }
    }
}

internal fun encodeJson(value: Any?): String =
    when (value) {
        null -> "null"
        is String -> "\"${escapeJson(value)}\""
        is Number, is Boolean -> value.toString()
        is Map<*, *> -> value.entries.joinToString(prefix = "{", postfix = "}") { (key, mapValue) ->
            "\"${escapeJson(key.toString())}\":${encodeJson(mapValue)}"
        }
        is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]") { encodeJson(it) }
        else -> "\"${escapeJson(value.toString())}\""
    }

private fun escapeJson(value: String): String =
    buildString(value.length) {
        value.forEach { ch ->
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (ch < ' ') {
                        append("\\u")
                        append(ch.code.toString(16).padStart(4, '0'))
                    } else {
                        append(ch)
                    }
                }
            }
        }
    }
