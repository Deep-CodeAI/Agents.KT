package agents_engine.whisper

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// #4508 — ADVERSARIAL: a malicious `name` must not let `fromUrl` write outside the
// cache directory. RED before the fix (the file escapes); GREEN after (rejected,
// nothing written outside).

@OptIn(ExperimentalPathApi::class)
class WhisperModelResolverSecurityTest {

    private val payload = "PWNED".toByteArray()
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/m") { ex ->
            ex.sendResponseHeaders(200, payload.size.toLong())
            ex.responseBody.use { it.write(payload) }
            ex.close()
        }
        executor = null
        start()
    }
    private val url get() = "http://localhost:${server.address.port}/m"

    @AfterTest fun stop() = server.stop(0)

    @Test
    fun `an absolute-path name cannot escape the cache dir`() {
        val cache = createTempDirectory("cache-abs")
        val outside = createTempDirectory("outside-abs")
        try {
            val sentinel = outside.resolve("pwned.bin") // attacker's chosen absolute target
            val resolver = WhisperModelResolver(cacheDir = cache)
            val ex = assertFailsWith<IllegalArgumentException> { resolver.fromUrl(sentinel.toString(), url) }
            // Pin INTENTIONAL validation (not the incidental createTempFile rejection).
            assertTrue("bare filename" in ex.message.orEmpty(), "rejected by our guard, not by accident: ${ex.message}")
            assertFalse(Files.exists(sentinel), "HARM: a model was written OUTSIDE the cache via an absolute name")
        } finally {
            cache.deleteRecursively(); outside.deleteRecursively()
        }
    }

    @Test
    fun `a traversing name cannot escape the cache dir`() {
        val cache = createTempDirectory("cache-trav")
        val outside = createTempDirectory("outside-trav")
        try {
            val resolver = WhisperModelResolver(cacheDir = cache)
            // ../<outside>/pwned.bin relative to the cache dir.
            val traversal = "../" + outside.fileName.toString() + "/pwned.bin"
            val ex = assertFailsWith<IllegalArgumentException> { resolver.fromUrl(traversal, url) }
            assertTrue("bare filename" in ex.message.orEmpty(), "rejected by our guard: ${ex.message}")
            assertFalse(Files.exists(outside.resolve("pwned.bin")), "HARM: traversal wrote outside the cache")
        } finally {
            cache.deleteRecursively(); outside.deleteRecursively()
        }
    }

    @Test
    fun `a plain filename still resolves normally`() {
        val cache = createTempDirectory("cache-ok")
        try {
            val path = WhisperModelResolver(cacheDir = cache).fromUrl("ggml-base.bin", url)
            assertTrue(path.startsWith(cache), "safe names stay in the cache dir")
            assertTrue(Files.isRegularFile(path))
        } finally {
            cache.deleteRecursively()
        }
    }
}
