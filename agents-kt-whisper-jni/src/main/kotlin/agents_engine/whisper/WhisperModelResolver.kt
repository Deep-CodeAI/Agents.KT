package agents_engine.whisper

import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.logging.Logger

/**
 * #4505 — resolves a Whisper model **file** at runtime, never bundling weights.
 * The jar is code; the `.bin` (a GGML Whisper model, hundreds of MB) is provisioned
 * here: use a local file if you have one ([fromPath]), or download-and-cache one
 * ([fromUrl]) into [cacheDir] with optional SHA-256 verification and reuse on later
 * runs. This is the build-time analogue of `BlobStore` — keep heavy bytes out of
 * the artifact.
 *
 * Licensing note: model weights are licensed by their authors, separately from this
 * Apache code. Downloading them is the deployer's action and responsibility.
 */
class WhisperModelResolver(
    val cacheDir: Path = defaultCacheDir(),
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build(),
    /** #4509 — hard cap on a downloaded model file; a larger response is rejected (disk-fill guard). */
    private val maxBytes: Long = DEFAULT_MAX_MODEL_BYTES,
) {
    /** Use an existing local model file; fail fast (actionably) if it isn't there. */
    fun fromPath(path: Path): Path {
        require(Files.isRegularFile(path)) {
            "Whisper model not found at '$path'. Download a GGML model (e.g. ggml-base.bin from " +
                "huggingface.co/ggerganov/whisper.cpp) and point modelPath at it."
        }
        return path
    }

    /**
     * Resolve `cacheDir/name`, downloading from [url] when absent (or when a present
     * file fails the [sha256] check). When [sha256] (lowercase hex) is given the
     * downloaded bytes are verified before the file is published — a mismatch fails
     * loud and leaves no partial file. Returns the cached path; subsequent calls with
     * a matching file reuse it without hitting the network.
     */
    fun fromUrl(name: String, url: String, sha256: String? = null): Path {
        requireSafeName(name)
        val target = cacheDir.resolve(name)
        if (Files.isRegularFile(target) && (sha256 == null || sha256Hex(target) == sha256.lowercase())) {
            return target
        }
        if (sha256 == null) {
            LOGGER.warning(
                "Whisper model '$name' from $url is being downloaded WITHOUT a pinned checksum — its " +
                    "integrity is not verified (a compromised mirror/MITM could serve malicious bytes to " +
                    "native whisper.cpp). Pass sha256 to verify.",
            )
        }
        Files.createDirectories(cacheDir)
        val tmp = Files.createTempFile(cacheDir, ".$name.", ".part")
        try {
            // #4509 — stream to disk with a hard byte cap (disk-fill guard); reject an over-cap
            // declared Content-Length up front, and abort mid-stream if an unsized body crosses it.
            val response = httpClient.send(
                java.net.http.HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream(),
            )
            check(response.statusCode() == HTTP_OK) {
                "Whisper model download from $url returned HTTP ${response.statusCode()}."
            }
            val declared = response.headers().firstValueAsLong("Content-Length").orElse(-1L)
            check(declared <= maxBytes) {
                "Whisper model at $url declares $declared bytes, over the $maxBytes-byte cap."
            }
            response.body().use { input -> Files.newOutputStream(tmp).use { out -> copyCapped(input, out, maxBytes) } }
            if (sha256 != null) {
                val actual = sha256Hex(tmp)
                check(actual == sha256.lowercase()) {
                    "Whisper model checksum mismatch for $url: expected $sha256, got $actual."
                }
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            return target
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    private fun copyCapped(input: InputStream, output: OutputStream, max: Long) {
        val buffer = ByteArray(BUFFER_BYTES)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            check(total <= max) { "Whisper model download exceeded the $max-byte cap." }
            output.write(buffer, 0, read)
        }
    }

    /**
     * #4508 — the cache filename must be a bare name. Without this, `cacheDir.resolve(name)`
     * with an absolute or `..`-laden [name] would escape the cache dir and download to an
     * arbitrary location (the previous code was only incidentally protected by `createTempFile`
     * rejecting separators — defense in depth makes it intentional and refactor-proof).
     */
    private fun requireSafeName(name: String) {
        require(
            name.isNotBlank() &&
                name != "." && name != ".." &&
                !name.contains('/') && !name.contains('\\') &&
                !Path.of(name).isAbsolute,
        ) {
            "Whisper model name '$name' must be a bare filename (no path separators or '..')."
        }
    }

    private fun sha256Hex(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { stream ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private val LOGGER: Logger = Logger.getLogger(WhisperModelResolver::class.java.name)
        private const val HTTP_OK = 200
        private const val BUFFER_BYTES = 1 shl 16

        /** Generous default download cap (8 GiB) — fits the largest GGML models; anti-abuse, not a quota. */
        const val DEFAULT_MAX_MODEL_BYTES = 8L * 1024 * 1024 * 1024

        /** `~/.cache/agents-kt/models` (honors `XDG_CACHE_HOME`). */
        fun defaultCacheDir(): Path {
            val base = System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() }
                ?: (System.getProperty("user.home") + "/.cache")
            return Path.of(base, "agents-kt", "models")
        }
    }
}
