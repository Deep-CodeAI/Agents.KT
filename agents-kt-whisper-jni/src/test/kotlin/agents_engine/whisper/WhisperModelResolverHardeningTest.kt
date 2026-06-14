package agents_engine.whisper

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// #4509 — hardening: follow redirects (real HF URLs 302 to a CDN), cap the download
// size (disk-fill), and warn when integrity isn't pinned.

@OptIn(ExperimentalPathApi::class)
class WhisperModelResolverHardeningTest {

    private val payload = "GGML-MODEL-BYTES".toByteArray()
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/model.bin") { ex ->
            ex.sendResponseHeaders(200, payload.size.toLong())
            ex.responseBody.use { it.write(payload) }
            ex.close()
        }
        createContext("/redirect") { ex ->
            ex.responseHeaders.add("Location", "http://localhost:${address.port}/model.bin")
            ex.sendResponseHeaders(302, -1)
            ex.close()
        }
        executor = null
        start()
    }
    private val base get() = "http://localhost:${server.address.port}"

    @AfterTest fun stop() = server.stop(0)

    @Test
    fun `fromUrl follows a redirect to the real download`() {
        val cache = createTempDirectory("redir")
        try {
            val path = WhisperModelResolver(cacheDir = cache).fromUrl("model.bin", "$base/redirect")
            assertEquals(payload.toList(), Files.readAllBytes(path).toList(), "302 must be followed to the CDN")
        } finally {
            cache.deleteRecursively()
        }
    }

    @Test
    fun `fromUrl rejects a download larger than the cap and leaves no file`() {
        val cache = createTempDirectory("cap")
        try {
            val resolver = WhisperModelResolver(cacheDir = cache, maxBytes = 4) // payload is 16 bytes
            val ex = assertFailsWith<IllegalStateException> { resolver.fromUrl("model.bin", "$base/model.bin") }
            assertTrue("cap" in ex.message.orEmpty() || "exceed" in ex.message.orEmpty(), "${ex.message}")
            assertFalse(Files.exists(cache.resolve("model.bin")), "HARM: an over-cap file was published")
        } finally {
            cache.deleteRecursively()
        }
    }

    @Test
    fun `a download without a pinned checksum warns about unverified integrity`() {
        val cache = createTempDirectory("warn")
        val logger = Logger.getLogger(WhisperModelResolver::class.java.name)
        val records = mutableListOf<LogRecord>()
        val handler = object : Handler() {
            override fun publish(record: LogRecord) { records.add(record) }
            override fun flush() {}
            override fun close() {}
        }
        logger.addHandler(handler)
        try {
            WhisperModelResolver(cacheDir = cache).fromUrl("model.bin", "$base/model.bin") // no sha256
            assertTrue(
                records.any { it.level == Level.WARNING && "checksum" in it.message.lowercase() },
                "missing-checksum download should warn; got: ${records.map { it.message }}",
            )
        } finally {
            logger.removeHandler(handler); cache.deleteRecursively()
        }
    }
}
