package agents_engine.core

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals

private const val MODEL = "gpt-oss:120b-cloud"
private const val HOST  = "localhost"
private const val PORT  = 11434

class FibonacciMemoryTest {

    private fun fibAgent(bank: MemoryBank) = agent<String, Int>("fibonacci") {
        // #980 — prompt lives in src/test/resources/prompts/fibonacci.md so
        // the long procedural text isn't trapped inside a Kotlin string literal.
        prompt(loadResource("prompts/fibonacci.md"))
        memory(bank)
        model { ollama(MODEL); host = HOST; port = PORT; temperature = 0.0 }
        budget { maxTurns = 5 }
        skills { skill<String, Int>("fib", "Generate next Fibonacci number") {
            tools()
            transformOutput { it.trim().toIntOrNull() ?: Regex("\\d+").find(it)?.value?.toInt() ?: error("No int in: $it") }
        }}
        onToolUse { name, args, result -> println("  [$name] args=$args → $result  (bank: ${bank.read("fibonacci")})") }
    }

    @Tag("live-llm")
    @Test
    fun `fibonacci via memory-only generates correct sequence`() {
        val bank = MemoryBank()
        val fib = fibAgent(bank)

        assertEquals(1, fib("do it"))
        assertEquals(1, fib("do it"))
        assertEquals(2, fib("do it"))
        assertEquals(3, fib("do it"))
        assertEquals(5, fib("do it"))
    }

    @Tag("live-llm")
    @Test
    fun `memory state progresses correctly`() {
        val bank = MemoryBank()
        val fib = fibAgent(bank)

        fib("do it"); assertEquals("0|1", bank.read("fibonacci"))
        fib("do it"); assertEquals("1|1", bank.read("fibonacci"))
        fib("do it"); assertEquals("1|2", bank.read("fibonacci"))
        fib("do it"); assertEquals("2|3", bank.read("fibonacci"))
    }

    @Tag("live-llm")
    @Test
    fun `pre-seeded memory resumes from arbitrary point`() {
        val bank = MemoryBank()
        bank.write("fibonacci", "21|34")
        val fib = fibAgent(bank)

        assertEquals(55,  fib("do it"))
        assertEquals(89,  fib("do it"))
        assertEquals(144, fib("do it"))
    }
}
