package agents_engine.whisper

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// #4505 — model provisioning WITHOUT bundling weights: download to a cache,
// verify SHA-256, reuse on the next call, fail loud on a checksum mismatch.

@OptIn(ExperimentalPathApi::class)
class WhisperModelResolverTest {

    private val modelBytes = "GGML-FAKE-WHISPER-MODEL".toByteArray()
    private val hits = AtomicInteger(0)
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/ggml-base.bin") { ex ->
            hits.incrementAndGet()
            ex.sendResponseHeaders(200, modelBytes.size.toLong())
            ex.responseBody.use { it.write(modelBytes) }
            ex.close()
        }
        executor = null
        start()
    }
    private val url get() = "http://localhost:${server.address.port}/ggml-base.bin"
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    @AfterTest fun stop() = server.stop(0)

    @Test
    fun `fromUrl downloads, verifies the checksum, and caches for reuse`() {
        val cache = createTempDirectory("whisper-cache")
        try {
            val resolver = WhisperModelResolver(cacheDir = cache)
            val path = resolver.fromUrl("ggml-base.bin", url, sha256 = sha256(modelBytes))
            assertTrue(Files.isRegularFile(path), "model file provisioned")
            assertEquals(modelBytes.toList(), path.readBytes().toList(), "bytes match what the server served")
            assertEquals(1, hits.get(), "one download")

            // Second resolve reuses the cached file — no second download.
            val again = resolver.fromUrl("ggml-base.bin", url, sha256 = sha256(modelBytes))
            assertEquals(path, again)
            assertEquals(1, hits.get(), "cache hit, server not re-contacted")
        } finally {
            cache.deleteRecursively()
        }
    }

    @Test
    fun `fromUrl fails loud on a checksum mismatch and leaves no file`() {
        val cache = createTempDirectory("whisper-cache2")
        try {
            val resolver = WhisperModelResolver(cacheDir = cache)
            val ex = assertFailsWith<IllegalStateException> {
                resolver.fromUrl("ggml-base.bin", url, sha256 = "deadbeef")
            }
            assertTrue("checksum mismatch" in ex.message.orEmpty(), "got: ${ex.message}")
            assertFalse(Files.exists(cache.resolve("ggml-base.bin")), "no partial/corrupt file published")
        } finally {
            cache.deleteRecursively()
        }
    }

    @Test
    fun `fromPath passes through an existing file and rejects a missing one`() {
        val cache = createTempDirectory("whisper-cache3")
        try {
            val model = cache.resolve("local.bin")
            model.writeBytes(modelBytes)
            val resolver = WhisperModelResolver(cacheDir = cache)
            assertEquals(model, resolver.fromPath(model))

            val ex = assertFailsWith<IllegalArgumentException> { resolver.fromPath(cache.resolve("nope.bin")) }
            assertTrue("not found" in ex.message.orEmpty(), "actionable: ${ex.message}")
        } finally {
            cache.deleteRecursively()
        }
    }

    @Test
    fun `default cache dir honors XDG and falls under agents-kt models`() {
        val dir = WhisperModelResolver.defaultCacheDir().toString()
        assertTrue(dir.endsWith("agents-kt/models") || dir.endsWith("agents-kt\\models"), dir)
    }
}
