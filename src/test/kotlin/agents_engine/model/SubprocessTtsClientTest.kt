package agents_engine.model

import agents_engine.content.AudioMime
import agents_engine.content.Content
import agents_engine.content.InMemoryBlobStore
import agents_engine.content.ToolResult
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// #4510 — local TTS via subprocess, no port, no JVM engine. A fake "TTS binary"
// (/bin/sh copying stdin to the output file) stands in for piper et al, so the test
// is hermetic; the real binary is the deployer's. POSIX only (uses /bin/sh).

@OptIn(ExperimentalPathApi::class)
@EnabledOnOs(OS.MAC, OS.LINUX)
class SubprocessTtsClientTest {

    /** Fake TTS: read the piped text from stdin and write it to the output file as the "audio". */
    private fun echoCommand(): (String, java.nio.file.Path) -> List<String> =
        { _, out -> listOf("/bin/sh", "-c", "cat > \"$1\"", "sh", out.toString()) }

    @Test
    fun `speak pipes text to the subprocess and returns the produced audio`() {
        val blobs = InMemoryBlobStore()
        val workDir = createTempDirectory("tts-ok")
        try {
            val tts = SubprocessTtsClient(blobs, mime = AudioMime.Wav, workDir = workDir, command = echoCommand())
            val audio = tts.speak("the meeting is at noon")

            assertEquals(AudioMime.Wav, audio.mime)
            assertEquals("the meeting is at noon", String(blobs.get(audio.ref)!!), "binary output became Content.Audio")
        } finally {
            workDir.deleteRecursively()
        }
    }

    @Test
    fun `it plugs into the speak tool and returns a ToolResult with the audio`() {
        val blobs = InMemoryBlobStore()
        val workDir = createTempDirectory("tts-tool")
        try {
            val tts = SubprocessTtsClient(blobs, workDir = workDir, command = echoCommand())
            val result = speakTool(tts).executor(mapOf("text" to "hello"))
            assertTrue(result is ToolResult)
            val audio = (result as ToolResult).parts.filterIsInstance<Content.Audio>().single()
            assertEquals("hello", String(blobs.get(audio.ref)!!))
        } finally {
            workDir.deleteRecursively()
        }
    }

    @Test
    fun `a failing TTS command surfaces an actionable error`() {
        val blobs = InMemoryBlobStore()
        val workDir = createTempDirectory("tts-fail")
        try {
            // Exits non-zero and writes nothing.
            val tts = SubprocessTtsClient(blobs, workDir = workDir) { _, _ -> listOf("/bin/sh", "-c", "exit 3") }
            val ex = assertFailsWith<IllegalStateException> { tts.speak("x") }
            assertTrue("failed" in ex.message.orEmpty() && "3" in ex.message.orEmpty(), "${ex.message}")
        } finally {
            workDir.deleteRecursively()
        }
    }

    @Test
    fun `blank text is rejected`() {
        val tts = SubprocessTtsClient(InMemoryBlobStore()) { _, _ -> listOf("/bin/true") }
        assertFailsWith<IllegalArgumentException> { tts.speak("   ") }
    }
}
