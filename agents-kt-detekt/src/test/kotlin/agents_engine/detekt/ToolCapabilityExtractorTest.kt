package agents_engine.detekt

import io.github.detekt.test.utils.compileContentForTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #2884 (epic #2882) — `ToolCapabilityExtractor` statically classifies what a tool's
 * executor body actually does (fs read/write, network, env, exec) by walking its call
 * expressions. The reusable input the #2887 comparator checks against the declared
 * `ToolPolicy`. Syntactic by design (callee-name match) — documented residual risk.
 */
class ToolCapabilityExtractorTest {

    private fun caps(code: String): Set<ToolCapability> =
        ToolCapabilityExtractor.extract(compileContentForTest(code))

    @Test fun `detects a filesystem write`() {
        assertEquals(setOf(ToolCapability.FS_WRITE), caps("""fun f() { java.io.File("/x").writeText("y") }"""))
    }

    @Test fun `detects a filesystem read`() {
        assertEquals(setOf(ToolCapability.FS_READ), caps("""fun f() { java.io.File("/x").readText() }"""))
    }

    @Test fun `detects Files write and read by their nio names`() {
        assertTrue(ToolCapability.FS_WRITE in caps("""fun f() { java.nio.file.Files.write(p, b) }"""))
        assertTrue(ToolCapability.FS_READ in caps("""fun f() { java.nio.file.Files.readAllBytes(p) }"""))
    }

    @Test fun `detects network`() {
        assertEquals(setOf(ToolCapability.NETWORK), caps("""fun f() { java.net.URL("http://x").openConnection() }"""))
    }

    @Test fun `detects environment access`() {
        assertEquals(setOf(ToolCapability.ENVIRONMENT), caps("""fun f() { System.getenv("HOME") }"""))
    }

    @Test fun `detects process exec`() {
        assertEquals(setOf(ToolCapability.EXEC), caps("""fun f() { ProcessBuilder("sh").start() }"""))
        assertTrue(ToolCapability.EXEC in caps("""fun f() { Runtime.getRuntime().exec("ls") }"""))
    }

    @Test fun `a pure-compute body has no capabilities`() {
        assertTrue(caps("""fun f() { val x = 1 + 2; println(x) }""").isEmpty())
    }

    @Test fun `a bare File handle without an io call is not a capability`() {
        // Constructing a File path is not, by itself, a read or a write.
        assertTrue(caps("""fun f() { val p = java.io.File("/x"); p.name }""").isEmpty())
    }

    @Test fun `combines multiple capabilities from one body`() {
        val c = caps("""fun f() { java.io.File("/x").writeText(System.getenv("Y") ?: "") }""")
        assertTrue(ToolCapability.FS_WRITE in c)
        assertTrue(ToolCapability.ENVIRONMENT in c)
    }
}
