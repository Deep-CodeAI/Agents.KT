package agents_engine.core

import agents_engine.model.LlmResponse
import agents_engine.model.ModelClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

// Tests for #970 — Agent.toString() and Agent.describe().
class AgentToStringTest {

    @Test
    fun `toString returns Agent named bracket form`() {
        val a = agent<String, String>("alice") {
            skills { skill<String, String>("greet", "Greet") {
                implementedBy { "hello" }
            } }
        }
        assertEquals("Agent<alice>", a.toString())
    }

    @Test
    fun `toString does not leak the JVM identity hash`() {
        val a = agent<String, String>("a") {
            skills { skill<String, String>("s", "s") { implementedBy { "x" } } }
        }
        // The default Object#toString contains "@<hex>" — ours must not.
        assertTrue(!a.toString().contains("@"), "toString must not include JVM identity hash: ${a.toString()}")
    }

    @Test
    fun `describe shows minimal agent (no model, no prompt, one skill)`() {
        val a = agent<String, String>("bare") {
            skills { skill<String, String>("op", "Operate") { implementedBy { "ok" } } }
        }
        val out = a.describe()
        assertTrue(out.contains("Agent<bare>"), "missing header: $out")
        assertTrue(out.contains("String"), "missing OUT type: $out")
        assertTrue(out.contains("prompt: (none)"), "expected (none) prompt: $out")
        assertTrue(out.contains("model: (none)"), "expected (none) model: $out")
        assertTrue(out.contains("budget: (defaults)"), "expected (defaults): $out")
        assertTrue(out.contains("skills (1):"), "expected skill count: $out")
        assertTrue(out.contains("op"), "expected skill name: $out")
        assertTrue(out.contains("memory: (none)"), "expected memory (none): $out")
    }

    @Test
    fun `describe truncates long prompt`() {
        val longPrompt = "x".repeat(200)
        val a = agent<String, String>("a") {
            prompt(longPrompt)
            skills { skill<String, String>("s", "s") { implementedBy { "ok" } } }
        }
        val out = a.describe()
        // Truncated form ends with "..." and total includes the original first 77 chars.
        assertTrue(out.contains("xxx..."), "expected truncation marker: $out")
        assertTrue(!out.contains("x".repeat(100)), "did not truncate: $out")
    }

    @Test
    fun `describe shows model config when configured`() {
        val a = agent<String, String>("a") {
            model {
                ollama("llama3")
                host = "example.com"
                port = 9999
                temperature = 0.42
                client = ModelClient { _ -> LlmResponse.Text("ok") }
            }
            skills { skill<String, String>("s", "s") { tools() } }
        }
        val out = a.describe()
        assertTrue(out.contains("ollama"), "expected provider: $out")
        assertTrue(out.contains("example.com:9999"), "expected host:port: $out")
        assertTrue(out.contains("llama3"), "expected model name: $out")
        assertTrue(out.contains("T=0.42"), "expected temperature: $out")
    }

    @Test
    fun `describe lists only overridden budget fields`() {
        val a = agent<String, String>("a") {
            budget {
                maxTurns = 20      // overridden
                maxDuration = 30.seconds  // overridden
                // maxToolCalls left at default
            }
            skills { skill<String, String>("s", "s") { implementedBy { "ok" } } }
        }
        val out = a.describe()
        assertTrue(out.contains("maxTurns=20"), "expected maxTurns override: $out")
        assertTrue(out.contains("maxDuration=30s"), "expected maxDuration override: $out")
        // Defaults must NOT show up.
        assertTrue(!out.contains("maxToolCalls=32"), "default maxToolCalls leaked: $out")
        assertTrue(!out.contains("(defaults)"), "had overrides; should not say defaults: $out")
    }

    @Test
    fun `describe shows multiple skills sorted by name`() {
        val a = agent<String, String>("a") {
            skills {
                skill<String, String>("zeta", "Z") { implementedBy { "z" } }
                skill<String, String>("alpha", "A") { implementedBy { "a" } }
                skill<String, String>("mid", "M") { implementedBy { "m" } }
            }
        }
        val out = a.describe()
        // Names appear sorted: alpha, mid, zeta — verify order.
        val skillsLine = out.lines().single { it.trimStart().startsWith("skills") }
        val alphaIdx = skillsLine.indexOf("alpha")
        val midIdx = skillsLine.indexOf("mid")
        val zetaIdx = skillsLine.indexOf("zeta")
        assertTrue(alphaIdx in 0..midIdx, "alpha should precede mid: $skillsLine")
        assertTrue(midIdx in 0..zetaIdx, "mid should precede zeta: $skillsLine")
    }

    @Test
    fun `describe reports memory when configured`() {
        val a = agent<String, String>("a") {
            memory(MemoryBank())
            skills { skill<String, String>("s", "s") { implementedBy { "ok" } } }
        }
        val out = a.describe()
        assertTrue(out.contains("memory: configured"), "expected memory configured: $out")
        // Memory tools should appear in the tools list.
        assertTrue(out.contains("memory_read"), "expected memory_read tool: $out")
        assertTrue(out.contains("memory_write"), "expected memory_write tool: $out")
    }

    @Test
    fun `describe is multi-line`() {
        val a = agent<String, String>("a") {
            skills { skill<String, String>("s", "s") { implementedBy { "ok" } } }
        }
        // Header + 6 indented lines minimum.
        val lineCount = a.describe().lines().size
        assertTrue(lineCount >= 6, "expected multi-line output, got $lineCount lines")
    }
}
