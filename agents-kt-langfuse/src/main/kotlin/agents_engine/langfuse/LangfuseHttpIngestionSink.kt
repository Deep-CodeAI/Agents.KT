package agents_engine.langfuse

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.util.Base64

internal class LangfuseHttpIngestionSink(
    publicKey: String,
    secretKey: String,
    baseUrl: String,
    private val client: HttpClient = HttpClient.newHttpClient(),
) : LangfuseIngestionSink {

    private val endpoint = URI.create(baseUrl.trimEnd('/') + "/api/public/ingestion")
    private val authHeader =
        "Basic " + Base64.getEncoder().encodeToString("$publicKey:$secretKey".toByteArray(StandardCharsets.UTF_8))

    override fun send(batch: List<LangfuseIngestionEvent>) {
        if (batch.isEmpty()) return
        val body = encodeJson(
            linkedMapOf(
                "batch" to batch.map { it.toWireMap() },
                "metadata" to linkedMapOf(
                    "sdkName" to "agents-kt",
                    "sdkIntegration" to "ObservabilityBridge",
                ),
            ),
        )
        val request = HttpRequest.newBuilder(endpoint)
            .header("content-type", "application/json")
            .header("authorization", authHeader)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw IOException("Langfuse ingestion failed: HTTP ${response.statusCode()} ${response.body()}")
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
