package agents_engine.core

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals

private const val MODEL = "gpt-oss:120b-cloud"
private const val HOST  = "localhost"
private const val PORT  = 11434

class FibonacciMemoryTest {

    private fun fibAgent(bank: MemoryBank) = agent<String, Int>("fibonacci") {
        prompt("""You maintain a Fibonacci sequence in memory.

Memory format: "prev|curr" (example: "5|8" means prev=5 curr=8).
Empty memory means no numbers generated yet.

PROCEDURE — do this EVERY time, no exceptions:
1. Call memory_read
2. Look at the result:
   - If empty → new prev=0, new curr=1, answer=1
   - If "A|B" → compute next=A+B, new prev=B, new curr=next, answer=next
3. Call memory_write with content "new_prev|new_curr"
4. Reply with ONLY the answer number

Worked examples:
  memory="" → answer=1, write "0|1"
  memory="0|1" → 0+1=1, answer=1, write "1|1"
  memory="1|1" → 1+1=2, answer=2, write "1|2"
  memory="1|2" → 1+2=3, answer=3, write "2|3"
  memory="2|3" → 2+3=5, answer=5, write "3|5"
  memory="21|34" → 21+34=55, answer=55, write "34|55"

Rules: exactly one memory_read, exactly one memory_write, then reply with just the number.""")
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
