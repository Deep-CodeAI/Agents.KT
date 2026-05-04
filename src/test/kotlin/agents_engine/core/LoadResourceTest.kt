package agents_engine.core

import org.junit.jupiter.api.assertThrows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Tests for #980 — loadResource(path) reads classpath resources at agent
// construction time. Missing resources throw IllegalArgumentException with a
// message that names the path; loadResourceOrNull returns null instead.
//
// Test fixtures live under src/test/resources/prompts/.
class LoadResourceTest {

    @Test
    fun `loadResource returns full content of a known resource`() {
        val content = loadResource("prompts/test-prompt.md")
        assertTrue(content.startsWith("# Test Agent"), "expected markdown header, got: $content")
        assertTrue(content.contains("rule one"), "expected first bullet, got: $content")
        assertTrue(content.contains("End of prompt."), "expected terminator, got: $content")
    }

    @Test
    fun `loadResource preserves multi-line structure verbatim`() {
        val content = loadResource("prompts/test-prompt.md")
        // Five blank-line-separated stanzas in the fixture.
        val nonBlankLines = content.lines().filter { it.isNotBlank() }
        assertTrue(nonBlankLines.size >= 4, "expected multi-line, got ${nonBlankLines.size} non-blank lines")
        // A blank line between header and bullets must survive.
        assertTrue(content.contains("\n\n"), "blank-line separator lost: $content")
    }

    @Test
    fun `loadResource decodes UTF-8 content correctly`() {
        val content = loadResource("prompts/utf8-prompt.txt")
        assertTrue(content.contains("Café"), "missing accented Latin: $content")
        assertTrue(content.contains("こんにちは"), "missing CJK: $content")
        assertTrue(content.contains("🚀"), "missing emoji: $content")
    }

    @Test
    fun `loadResource throws IllegalArgumentException when resource is missing`() {
        val ex = assertThrows<IllegalArgumentException> {
            loadResource("prompts/does-not-exist.md")
        }
        // Message must name the missing path so the user can find their typo.
        val msg = ex.message.orEmpty()
        assertTrue(
            msg.contains("prompts/does-not-exist.md"),
            "message should name the missing path: $msg",
        )
    }

    @Test
    fun `loadResource normalizes a leading slash to the same lookup`() {
        // Both "prompts/test-prompt.md" and "/prompts/test-prompt.md" should
        // resolve to the same resource — the JVM API is picky about the
        // leading slash and we normalize so users don't have to remember.
        val noSlash = loadResource("prompts/test-prompt.md")
        val withSlash = loadResource("/prompts/test-prompt.md")
        assertEquals(noSlash, withSlash)
    }

    @Test
    fun `loadResource accepts an empty file (returns empty string)`() {
        // An empty .txt is a legal resource — must return "" rather than
        // throwing or returning null.
        val content = loadResource("prompts/empty.txt")
        assertEquals("", content)
    }

    @Test
    fun `loadResourceOrNull returns content for an existing resource`() {
        val content = loadResourceOrNull("prompts/test-prompt.md")
        assertNotNull(content)
        assertTrue(content.startsWith("# Test Agent"))
    }

    @Test
    fun `loadResourceOrNull returns null for a missing resource (does not throw)`() {
        val content = loadResourceOrNull("prompts/does-not-exist.md")
        assertNull(content)
    }

    @Test
    fun `loadResource — agent construction integrates cleanly`() {
        // Smoke test of the canonical usage pattern: agent { prompt(loadResource(...)) }.
        // If the resource is missing, agent construction must fail (that's the
        // fail-fast contract), not the first invocation.
        val a = agent<String, String>("test") {
            prompt(loadResource("prompts/test-prompt.md"))
            skills { skill<String, String>("op", "op") { implementedBy { "ok" } } }
        }
        assertTrue(a.prompt.startsWith("# Test Agent"))
    }

    @Test
    fun `loadResource — missing resource fails at agent construction (fail-fast)`() {
        // Verify the fail-fast contract: a typo in the resource path surfaces
        // when agent { } runs, not on the first agent invocation.
        assertThrows<IllegalArgumentException> {
            agent<String, String>("test") {
                prompt(loadResource("prompts/typo-here.md"))
                skills { skill<String, String>("op", "op") { implementedBy { "ok" } } }
            }
        }
    }
}
