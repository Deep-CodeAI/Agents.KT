package agents_engine.model

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.util.Optional
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// #4560 — default transient-network retry in the shared HTTP transport. Hermetic: a scripted fake
// HttpClient drives sendBounded through retry on IOException / transient status, no-retry on 4xx, and
// exhaustion. All provider adapters inherit this via HttpModelClientSupport.sendBounded.
class HttpModelClientSupportTest {

    private fun ok(body: String) = FakeResponse(200, body)
    private fun status(code: Int) = FakeResponse(code, """{"error":"$code"}""")
    private val request = HttpRequest.newBuilder().uri(URI.create("http://test/")).GET().build()

    private fun send(vararg steps: () -> HttpResponse<InputStream>): Pair<String, Int> {
        val client = ScriptedHttpClient(steps.toMutableList())
        val body = HttpModelClientSupport.sendBounded(client, request, "Test", 4096)
        return body to client.calls
    }

    @Test
    fun `retries network exceptions then succeeds`() {
        val (body, calls) = send(
            { throw IOException("connection reset") },
            { throw IOException("timeout") },
            { ok("done") },
        )
        assertEquals("done", body)
        assertEquals(3, calls)
    }

    @Test
    fun `retries a transient status then succeeds`() {
        val (body, calls) = send({ status(503) }, { ok("recovered") })
        assertEquals("recovered", body)
        assertEquals(2, calls)
    }

    @Test
    fun `rethrows the original exception (type preserved) after exhausting attempts`() {
        val reset = { throw IOException("reset") }
        val e = assertFailsWith<IOException> { send(reset, reset, reset) }
        assertEquals("reset", e.message) // original preserved so onLLMError can match e is ConnectException, etc.
    }

    @Test
    fun `does not retry a timeout (respects the configured per-request budget)`() {
        val client = ScriptedHttpClient(mutableListOf({ throw HttpTimeoutException("timed out") }, { ok("nope") }))
        assertFailsWith<HttpTimeoutException> { HttpModelClientSupport.sendBounded(client, request, "Test", 4096) }
        assertEquals(1, client.calls, "a timeout must surface immediately, not be retried")
    }

    @Test
    fun `does not retry a non-transient 4xx and returns its body for downstream parsing`() {
        val (body, calls) = send({ status(400) }, { ok("should-not-reach") })
        assertTrue("400" in body, body)
        assertEquals(1, calls, "4xx must not be retried")
    }

    @Test
    fun `returns the final transient-status body instead of masking the provider error`() {
        // 503 every time → after MAX_ATTEMPTS, the last body is returned so the per-provider parser can
        // surface the real error message rather than a generic transport error.
        val (body, calls) = send({ status(503) }, { status(503) }, { status(503) })
        assertTrue("503" in body, body)
        assertEquals(3, calls)
    }

    // --- test doubles ---

    private class FakeResponse(private val status: Int, body: String) : HttpResponse<InputStream> {
        private val bytes = body.toByteArray()
        override fun statusCode() = status
        override fun body(): InputStream = ByteArrayInputStream(bytes)
        override fun request(): HttpRequest = HttpRequest.newBuilder().uri(URI.create("http://test/")).build()
        override fun previousResponse(): Optional<HttpResponse<InputStream>> = Optional.empty()
        override fun headers(): HttpHeaders = HttpHeaders.of(emptyMap()) { _, _ -> true }
        override fun sslSession() = Optional.empty<javax.net.ssl.SSLSession>()
        override fun uri(): URI = URI.create("http://test/")
        override fun version(): HttpClient.Version = HttpClient.Version.HTTP_1_1
    }

    private class ScriptedHttpClient(private val steps: MutableList<() -> HttpResponse<InputStream>>) : HttpClient() {
        var calls = 0

        @Suppress("UNCHECKED_CAST")
        override fun <T> send(req: HttpRequest, handler: HttpResponse.BodyHandler<T>): HttpResponse<T> {
            calls++
            return steps.removeAt(0).invoke() as HttpResponse<T>
        }

        override fun cookieHandler() = Optional.empty<java.net.CookieHandler>()
        override fun connectTimeout() = Optional.empty<java.time.Duration>()
        override fun followRedirects(): Redirect = Redirect.NEVER
        override fun proxy() = Optional.empty<java.net.ProxySelector>()
        override fun sslContext(): SSLContext = SSLContext.getDefault()
        override fun sslParameters(): SSLParameters = SSLParameters()
        override fun authenticator() = Optional.empty<java.net.Authenticator>()
        override fun version(): Version = Version.HTTP_1_1
        override fun executor() = Optional.empty<java.util.concurrent.Executor>()
        override fun <T> sendAsync(req: HttpRequest, handler: HttpResponse.BodyHandler<T>) = error("unused")
        override fun <T> sendAsync(
            req: HttpRequest,
            handler: HttpResponse.BodyHandler<T>,
            push: HttpResponse.PushPromiseHandler<T>?,
        ) = error("unused")
        override fun newWebSocketBuilder() = error("unused")
    }
}
