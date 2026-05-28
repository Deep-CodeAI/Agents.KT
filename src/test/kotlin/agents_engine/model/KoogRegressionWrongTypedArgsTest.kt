package agents_engine.model

import agents_engine.core.agent
import agents_engine.generation.Generable
import agents_engine.generation.Guide
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * #2475 (under Koog regression epic #2474) — malformed tool args are a
 * RECOVERABLE typed error, never a raw serialization exception that
 * escapes the agentic loop.
 *
 * Two contracts pinned:
 *
 * 1. **Coercion contract** — scalar `Number → String` is intentional per
 *    `coerceValue` (`String::class -> value.toString()`), not a malformed
 *    arg. The Koog signal "wrong type kills the pipeline" does NOT apply
 *    here; the executor runs with the stringified value and the loop
 *    continues. (Regression target: if someone tightens the coerce
 *    function, this test catches the behavior change.)
 *
 * 2. **Recovery contract** — a value that genuinely can't be coerced
 *    (e.g., an unparseable `"abc"` for an `Int` field) DOES route through
 *    `onError.invalidArgs`, the executor never runs with bad data, and
 *    without a handler the failure is the framework's typed
 *    `ToolExecutionException` — never a raw `kotlinx.serialization` /
 *    `NumberFormatException`.
 *
 * Companion to `TypedToolValidationRoutingTest`, which pins the
 * MISSING-field path. This test pins the WRONG-TYPED-but-uncoercible path.
 */
class KoogRegressionWrongTypedArgsTest {

    @Generable("A search request with a count")
    data class SearchArgs(
        @Guide("Search query string") val query: String,
        @Guide("Max results to return") val count: Int,
    )

    @Generable
    data class SearchResult(val matches: List<String>)

    @Test
    fun `number passed for String field coerces and the executor runs (no malformed-arg error)`() {
        // Contract pin: the LLM-emitted Number reaches the executor as a
        // stringified value, NOT as an invalidArgs route. This is the
        // documented coerceValue behavior (#855 / GenerableSupport).
        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.ToolCalls(listOf(
            ToolCall(name = "search", arguments = mapOf("query" to 42, "count" to 3))
        )))
        responses.add(LlmResponse.Text("done"))
        val mock = ModelClient { _ -> responses.removeFirst() }

        var seenQuery: String? = null
        var invalidArgsFired = false
        val a = agent<String, String>("a") {
            lateinit var search: Tool<SearchArgs, SearchResult>
            model { ollama("llama3"); client = mock }
            tools {
                search = tool<SearchArgs, SearchResult>("search", "Search") { args ->
                    seenQuery = args.query
                    SearchResult(listOf("ok"))
                }
            }
            skills { skill<String, String>("s", "stub") { tools(search) } }
            onToolError("search") {
                invalidArgs { _, _ -> invalidArgsFired = true; null }
            }
        }

        val out = a("input")

        assertEquals("done", out)
        assertEquals("42", seenQuery, "Number → String coercion is intentional; executor sees the stringified value")
        assertTrue(!invalidArgsFired, "Number → String must not route through invalidArgs")
    }

    @Test
    fun `unparseable String for an Int field routes through invalidArgs with a model-visible recovery`() {
        // Contract pin: the recovery path is exercised end-to-end.
        // - First turn: bad args ("abc" for count: Int) → invalidArgs handler returns Fixed.
        // - Second turn: fixed args → executor runs, loop produces "done".
        // - The executor must run EXACTLY ONCE (for the repaired call).
        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.ToolCalls(listOf(
            ToolCall(name = "search", arguments = mapOf("query" to "hello", "count" to "abc"))
        )))
        // After Fixed() the framework re-tries WITHOUT another LLM round trip,
        // so we only need the trailing text response.
        responses.add(LlmResponse.Text("done"))
        val mock = ModelClient { _ -> responses.removeFirst() }

        var executorCalls = 0
        var capturedError: String? = null
        val a = agent<String, String>("a") {
            lateinit var search: Tool<SearchArgs, SearchResult>
            model { ollama("llama3"); client = mock }
            tools {
                search = tool<SearchArgs, SearchResult>("search", "Search") { args ->
                    executorCalls++
                    SearchResult(listOf("for ${args.query}"))
                }
            }
            skills { skill<String, String>("s", "stub") { tools(search) } }
            onToolError("search") {
                invalidArgs { _, error ->
                    capturedError = error
                    RepairResult.Fixed("""{"query":"hello","count":3}""")
                }
            }
        }

        val out = a("input")

        assertEquals("done", out, "loop must exit cleanly after recovery")
        assertEquals(1, executorCalls, "executor must run exactly once — for the repaired call, never with bad args")
        val err = capturedError ?: fail("invalidArgs handler must fire for an unparseable Int field")
        assertTrue(
            err.contains("SearchArgs", ignoreCase = true) ||
                err.contains("count", ignoreCase = true) ||
                err.contains("deserialize", ignoreCase = true),
            "error must surface the typed-arg failure context: $err",
        )
    }

    @Test
    fun `unparseable Int with no handler surfaces a framework ToolExecutionException, not a raw kotlinx serialization exception`() {
        // Even WITHOUT an onError handler, the failure must be the framework's
        // typed ToolExecutionException — never a raw NumberFormatException /
        // SerializationException that a user wouldn't expect from the agent
        // boundary.
        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.ToolCalls(listOf(
            ToolCall(name = "search", arguments = mapOf("query" to "hello", "count" to "abc"))
        )))
        responses.add(LlmResponse.Text("done"))
        val mock = ModelClient { _ -> responses.removeFirst() }

        val a = agent<String, String>("a") {
            lateinit var search: Tool<SearchArgs, SearchResult>
            model { ollama("llama3"); client = mock }
            tools {
                search = tool<SearchArgs, SearchResult>("search", "Search") { args ->
                    SearchResult(listOf(args.query))
                }
            }
            skills { skill<String, String>("s", "stub") { tools(search) } }
        }

        try {
            a("input")
            fail("expected ToolExecutionException for unparseable Int without onError handler")
        } catch (e: Throwable) {
            assertTrue(
                e is ToolExecutionException ||
                    generateSequence(e) { it.cause }.any { it is ToolExecutionException },
                "failure must be ToolExecutionException (or wrap one); got ${e::class.qualifiedName}: ${e.message}",
            )
            val msgChain = (e.message ?: "") + " " +
                generateSequence(e) { it.cause }.joinToString(" | ") { it.message ?: it::class.simpleName.orEmpty() }
            assertTrue(
                msgChain.contains("search", ignoreCase = true) ||
                    msgChain.contains("SearchArgs", ignoreCase = true) ||
                    msgChain.contains("count", ignoreCase = true) ||
                    msgChain.contains("deserialize", ignoreCase = true),
                "message must point at the typed-arg failure context: $msgChain",
            )
        }
    }
}
