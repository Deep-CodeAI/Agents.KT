package agents_engine.a2a

import agents_engine.core.Agent
import agents_engine.core.agent
import agents_engine.generation.LenientJsonParser
import agents_engine.generation.codec
import agents_engine.generation.hasGenerableAnnotation
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.reflect.KClass

/**
 * `agents_engine/a2a/A2AClient.kt` — #3864. A typed handle on a remote
 * A2A agent. `a2aAgent<IN, OUT>(name, url)` returns a real
 * `Agent<IN, OUT>` whose single deterministic skill performs the
 * JSON-RPC `message/send` round-trip — so the remote drops into
 * composition (`then` / `/` / `forum` / `branch`) and skill allowlists
 * exactly like a local agent.
 *
 * Typing across the wire (v1): IN of `String` goes as the raw text part;
 * `@Generable` IN is JSON-encoded (flat property map). OUT of `String`
 * is the artifact text verbatim; `@Generable` OUT is lenient-JSON-decoded
 * from it. Streaming (`message/stream`) is a #3864 follow-up.
 */
inline fun <reified IN : Any, reified OUT : Any> a2aAgent(
    name: String,
    url: String,
    bearerToken: String? = null,
    timeoutSeconds: Long = A2A_DEFAULT_TIMEOUT_SECONDS,
): Agent<IN, OUT> {
    val transport = A2ATransport(url, bearerToken, timeoutSeconds)
    return agent(name) {
        skills {
            skill<IN, OUT>("a2a-call", "Remote A2A agent at $url") {
                implementedBy { input ->
                    decodeA2AOutput(transport.sendMessage(encodeA2AInput(input)), OUT::class)
                }
            }
        }
    }
}

@PublishedApi
internal fun encodeA2AInput(input: Any): String = when {
    input is String -> input
    A2AJson.isSimple(input::class) -> input.toString()
    else -> A2AJson.encodeTyped(input)
}

@PublishedApi
internal fun <OUT : Any> decodeA2AOutput(text: String, outType: KClass<OUT>): OUT = when {
    outType == String::class -> @Suppress("UNCHECKED_CAST") (text as OUT)
    outType.hasGenerableAnnotation() -> {
        val fields = LenientJsonParser.parse(text) as? Map<*, *>
            ?: error("expected a JSON object for @Generable ${outType.simpleName}; got: $text")
        outType.codec().decode(fields)
            ?: error("could not deserialize @Generable ${outType.simpleName} from: $text")
    }
    else -> error("Unsupported A2A OUT type ${outType.simpleName}. Use String or a @Generable class.")
}

/** Blocking JSON-RPC transport for the v1 message/send round-trip. */
@PublishedApi
internal class A2ATransport(
    private val url: String,
    private val bearerToken: String?,
    timeoutSeconds: Long,
) {
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(timeoutSeconds))
        .build()
    private val timeout = Duration.ofSeconds(timeoutSeconds)

    fun sendMessage(text: String): String {
        val body = """{"jsonrpc":"2.0","id":"1","method":"message/send","params":{"message":""" +
            """{"role":"user","parts":[{"kind":"text","text":${A2AJson.encode(text)}}]}}}"""
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .apply { bearerToken?.let { header("Authorization", "Bearer $it") } }
            // #3873 — W3C trace context across the A2A boundary.
            .apply {
                agents_engine.core.TraceContextPropagation.outboundHeaders()
                    .forEach { (k, v) -> header(k, v) }
            }
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == HTTP_OK) {
            "A2A endpoint $url returned HTTP ${response.statusCode()}: ${response.body().take(ERROR_PREVIEW_CHARS)}"
        }
        val parsed = LenientJsonParser.parse(response.body()) as? Map<*, *>
            ?: error("A2A endpoint $url returned non-JSON: ${response.body().take(ERROR_PREVIEW_CHARS)}")
        (parsed["error"] as? Map<*, *>)?.let { rpcError ->
            error("A2A call failed: ${rpcError["message"]} (code ${rpcError["code"]})")
        }
        val result = parsed["result"] as? Map<*, *> ?: error("A2A response has no result: $parsed")
        val artifacts = result["artifacts"] as? List<*> ?: error("A2A task has no artifacts: $result")
        val parts = (artifacts.firstOrNull() as? Map<*, *>)?.get("parts") as? List<*>
            ?: error("A2A artifact has no parts: $artifacts")
        return parts.filterIsInstance<Map<*, *>>()
            .firstOrNull { it["kind"] == "text" || it["type"] == "text" }
            ?.get("text") as? String
            ?: error("A2A artifact has no text part: $parts")
    }

    private companion object {
        const val HTTP_OK = 200
        const val ERROR_PREVIEW_CHARS = 200
    }
}

const val A2A_DEFAULT_TIMEOUT_SECONDS: Long = 30
