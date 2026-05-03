package agents_engine.composition.forum

import agents_engine.core.agent
import agents_engine.generation.Generable
import agents_engine.generation.Guide
import agents_engine.model.LlmResponse
import agents_engine.model.ModelClient
import agents_engine.model.ToolCall
import org.junit.jupiter.api.assertThrows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Tests for #888 — direct coverage of Forum.castForumReturn. The captain
// emits `forum_return(value=...)`, the framework throws ForumReturnException
// internally, and Forum.invokeSuspend's catch block routes the value through
// castForumReturn(). Different `value` types exercise each branch.

@Generable("forum verdict shape")
data class ForumVerdict(@Guide("the answer") val answer: String)

class ForumCastReturnTest {

    private fun participant() = agent<String, String>("p") {
        skills { skill<String, String>("p") { implementedBy { "participant-said-$it" } } }
    }

    private fun captainEmitting(toolArgs: Map<String, Any?>) = agent<String, String>("c") {
        model {
            ollama("test")
            client = ModelClient { _ ->
                LlmResponse.ToolCalls(listOf(ToolCall("forum_return", toolArgs)))
            }
        }
        skills { skill<String, String>("c") { tools() } }
    }

    private inline fun <reified T : Any> typedCaptainEmitting(toolArgs: Map<String, Any?>) =
        agent<String, T>("c") {
            model {
                ollama("test")
                client = ModelClient { _ ->
                    LlmResponse.ToolCalls(listOf(ToolCall("forum_return", toolArgs)))
                }
            }
            skills { skill<String, T>("c") { tools() } }
        }

    // L84 — outType == String, value is non-String (toString cast)

    @Test
    fun `castForumReturn outType String coerces non-String via toString`() {
        val result = forum<String, String> {
            participant(participant())
            captain(captainEmitting(mapOf("value" to 42)))
        }("topic")
        assertEquals("42", result)
    }

    // L85 — outType.java.isInstance(raw) — direct pass-through

    @Test
    fun `castForumReturn passes through when raw is already an instance of OUT`() {
        // OUT=Int, value=42 (Int). outType==String fails, then
        // Int::class.java.isInstance(42) succeeds → cast through.
        val result = forum<String, Int> {
            participant(participant())
            captain(typedCaptainEmitting<Int>(mapOf("value" to 42)))
        }("topic")
        assertEquals(42, result)
    }

    // L87-89 — raw is Map → constructFromMap → success

    @Test
    fun `castForumReturn constructs Generable from Map raw value`() {
        val result = forum<String, ForumVerdict> {
            participant(participant())
            captain(typedCaptainEmitting<ForumVerdict>(mapOf("value" to mapOf("answer" to "hello"))))
        }("topic")
        assertEquals("hello", result.answer)
    }

    // L89 ?: error — raw is Map but constructFromMap fails

    @Test
    fun `castForumReturn errors when Map cannot be constructed into Generable`() {
        val ex = assertThrows<IllegalStateException> {
            forum<String, ForumVerdict> {
                participant(participant())
                captain(
                    typedCaptainEmitting<ForumVerdict>(
                        mapOf("value" to mapOf("wrongField" to "boom")),
                    ),
                )
            }("topic")
        }
        assertTrue(
            ex.message!!.contains("ForumVerdict") && ex.message!!.contains("could not be parsed"),
            "expected error to name the type: ${ex.message}",
        )
    }

    // L91-93 — raw is String → fromLlmOutput → success

    @Test
    fun `castForumReturn parses Generable from JSON String raw value`() {
        val result = forum<String, ForumVerdict> {
            participant(participant())
            captain(
                typedCaptainEmitting<ForumVerdict>(
                    mapOf("value" to """{"answer":"world"}"""),
                ),
            )
        }("topic")
        assertEquals("world", result.answer)
    }

    // L93 ?: error — raw is String but fromLlmOutput fails

    @Test
    fun `castForumReturn errors when String cannot be parsed as Generable`() {
        val ex = assertThrows<IllegalStateException> {
            forum<String, ForumVerdict> {
                participant(participant())
                captain(
                    typedCaptainEmitting<ForumVerdict>(
                        mapOf("value" to "not even close to JSON"),
                    ),
                )
            }("topic")
        }
        assertTrue(
            ex.message!!.contains("ForumVerdict"),
            "expected error to name the type: ${ex.message}",
        )
    }

    // L95 — catch-all: raw is none of String/Map/instance-of-OUT

    @Test
    fun `castForumReturn errors when raw is incompatible with OUT (catch-all)`() {
        val ex = assertThrows<IllegalStateException> {
            forum<String, ForumVerdict> {
                participant(participant())
                captain(
                    // raw = Int 42, OUT = ForumVerdict
                    // outType==String? no
                    // ForumVerdict.java.isInstance(42)? no
                    // raw is Map? no
                    // raw is String? no
                    // → catch-all error fires
                    typedCaptainEmitting<ForumVerdict>(mapOf("value" to 42)),
                )
            }("topic")
        }
        assertTrue(
            ex.message!!.contains("incompatible"),
            "expected catch-all error wording: ${ex.message}",
        )
        assertTrue(
            ex.message!!.contains("ForumVerdict"),
            "error must name OUT type: ${ex.message}",
        )
    }
}
