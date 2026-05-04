package agents_engine.model

import agents_engine.core.agent
import agents_engine.core.skill
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Wider live-LLM coverage of the #706 inline-tool fallback against `gemma3:4b`
 * (no native tool support). Exercises classic agent demos — parenthesized
 * arithmetic and Fibonacci — through the inline JSON tool-call path that
 * `OllamaClient.chat` swaps in when Ollama reports the model can't accept
 * native tools.
 *
 * What's actually being verified: the framework drives a small, no-tool-
 * support model into emitting valid `{"tool":...,"arguments":...}` JSON,
 * the framework parses it, runs the tool, and the agentic loop completes.
 * Assertions are deliberately loose on the model's free-form final text
 * (gemma3:4b is small) and tight on what we control: tool was invoked
 * with structurally sensible arguments.
 *
 * Tagged `live-llm` — excluded from `./gradlew test`, run via
 * `./gradlew integrationTest`. Requires Ollama on localhost:11434 with
 * `gemma3:4b` pulled.
 */
class OllamaGemmaFallbackScenariosTest {

    @Tag("live-llm")
    @Test
    fun `gemma3 4b solves parenthesized arithmetic via evaluate tool`() {
        val callsLog = mutableListOf<String>()

        val a = agent<String, String>("calc") {
            lateinit var evaluate: Tool<Map<String, Any?>, Any?>
            prompt(
                "To answer any math question, call the `evaluate` tool ONCE with the full " +
                    "arithmetic expression as the `expression` argument. Then reply with the result."
            )
            model { ollama("gemma3:4b"); host = "localhost"; port = 11434; temperature = 0.0 }
            tools {
                evaluate = tool(
                    "evaluate",
                    "Evaluate an arithmetic expression with + - * / and parentheses. Arguments: {expression: string}",
                ) { args ->
                    val expr = (args["expression"] as? String).orEmpty()
                    callsLog.add(expr)
                    (evalArith(expr) ?: "ERROR").toString()
                }
            }
            skills {
                skill<String, String>("calc", "Compute arithmetic via the evaluate tool") { tools(evaluate) }
            }
        }

        a("Compute (2+3)*4")

        assertTrue(callsLog.isNotEmpty(), "evaluate tool must be called via inline fallback (got 0 calls)")
        val firstExpr = callsLog.first()
        val value = evalArith(firstExpr)
        assertTrue(
            value == 20L,
            "model should send an expression equal to 20 (got: '$firstExpr' = $value, all calls: $callsLog)",
        )
    }

    @Tag("live-llm")
    @Test
    fun `gemma3 4b computes 10th Fibonacci via fib tool`() {
        val nsAsked = mutableListOf<Int>()

        val a = agent<String, String>("fib") {
            lateinit var fibTool: Tool<Map<String, Any?>, Any?>
            prompt(
                "To compute a Fibonacci number, call the `fib` tool ONCE with the index as " +
                    "the `n` argument. Then reply with the result. " +
                    "Indexing convention: fib(0)=0, fib(1)=1, fib(2)=1, fib(10)=55."
            )
            model { ollama("gemma3:4b"); host = "localhost"; port = 11434; temperature = 0.0 }
            tools {
                fibTool = tool(
                    "fib",
                    "Compute the Nth Fibonacci number (0-indexed). Arguments: {n: integer}",
                ) { args ->
                    val n = (args["n"] as? Number)?.toInt() ?: error("fib called without integer n: $args")
                    nsAsked.add(n)
                    fib(n).toString()
                }
            }
            skills {
                skill<String, String>("fib", "Compute Fibonacci via the fib tool") { tools(fibTool) }
            }
        }

        a("What is the 10th Fibonacci number?")

        assertTrue(nsAsked.isNotEmpty(), "fib tool must be called via inline fallback (got 0 calls)")
        // Loose: small models may interpret "10th" as 0- or 1-indexed; accept 9..11.
        val n = nsAsked.first()
        assertTrue(
            n in 9..11,
            "model should call fib with n around 10 (got n=$n; all calls: $nsAsked)",
        )
    }

    private fun fib(n: Int): Long {
        require(n >= 0) { "fib(n) requires n >= 0, got $n" }
        if (n <= 1) return n.toLong()
        var a = 0L
        var b = 1L
        repeat(n - 1) {
            val t = a + b; a = b; b = t
        }
        return b
    }

    private fun evalArith(expr: String): Long? = runCatching {
        val p = ArithParser(expr)
        val v = p.expr()
        check(p.atEnd()) { "trailing input at ${p.pos}: $expr" }
        v
    }.getOrNull()

    /** Tiny recursive-descent evaluator: + - * / and parens, integer arithmetic. */
    private class ArithParser(private val s: String) {
        var pos = 0
            private set
        private fun peek(): Char? = if (pos < s.length) s[pos] else null
        private fun skipWs() { while (peek()?.isWhitespace() == true) pos++ }
        fun atEnd(): Boolean { skipWs(); return pos >= s.length }

        fun expr(): Long {
            var v = term()
            while (true) {
                skipWs()
                val c = peek() ?: break
                if (c != '+' && c != '-') break
                pos++
                val r = term()
                v = if (c == '+') v + r else v - r
            }
            return v
        }

        private fun term(): Long {
            var v = factor()
            while (true) {
                skipWs()
                val c = peek() ?: break
                if (c != '*' && c != '/') break
                pos++
                val r = factor()
                v = if (c == '*') v * r else v / r
            }
            return v
        }

        private fun factor(): Long {
            skipWs()
            if (peek() == '(') {
                pos++
                val v = expr()
                skipWs()
                check(peek() == ')') { "missing ')' at $pos in: $s" }
                pos++
                return v
            }
            if (peek() == '-') { pos++; return -factor() }
            val start = pos
            while (peek()?.isDigit() == true) pos++
            check(pos > start) { "expected digit at $pos in: $s" }
            return s.substring(start, pos).toLong()
        }
    }
}
