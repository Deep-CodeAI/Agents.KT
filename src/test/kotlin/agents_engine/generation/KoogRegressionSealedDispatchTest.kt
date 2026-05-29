package agents_engine.generation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * #2482a (under Koog regression epic #2474) — sealed `@Generable` parent
 * classes must deserialize from a `{type: VariantName, ...}` map by
 * dispatching to the matching variant. This is the inverse of the
 * `oneOf` + `const` schema Agents.KT already emits for sealed types
 * (`sealedJsonSchema` produces `{oneOf:[{type:{const:"V1"},...},...]}`)
 * — without dispatch, the LLM is shown a `oneOf` schema it can satisfy
 * but the server can't read.
 *
 * Koog signal: MCP schema parser supported anyOf but not oneOf. In
 * Agents.KT terms: sealed-parent construction lands the missing half.
 */
class KoogRegressionSealedDispatchTest {

    @Generable("An action the user can take")
    sealed interface Action {
        @Generable("Send a message")
        data class SendMessage(@Guide("Message body") val text: String) : Action

        @Generable("Set a timer")
        data class SetTimer(@Guide("Delay in seconds") val seconds: Int) : Action

        @Generable("Cancel everything in flight")
        data object Cancel : Action
    }

    @Test
    fun `sealed parent construct from map dispatches on the type discriminator`() {
        val sent = Action::class.constructFromMap(
            mapOf("type" to "SendMessage", "text" to "hello world")
        )
        val msg = assertNotNull(sent, "SendMessage variant must be reachable from the parent class")
        assertEquals(Action.SendMessage("hello world"), msg)
    }

    @Test
    fun `sealed parent dispatches to a different variant on a different discriminator`() {
        val timer = Action::class.constructFromMap(
            mapOf("type" to "SetTimer", "seconds" to 30)
        )
        assertEquals(Action.SetTimer(30), timer)
    }

    @Test
    fun `sealed parent rejects unknown variant discriminator`() {
        val unknown = Action::class.constructFromMap(
            mapOf("type" to "DoesNotExist", "text" to "x")
        )
        assertNull(unknown, "an unknown variant name must produce null, not a partial / silent construction")
    }

    @Test
    fun `sealed parent rejects a missing type discriminator`() {
        val missing = Action::class.constructFromMap(
            mapOf("text" to "no type")
        )
        assertNull(missing, "a map with no type discriminator cannot select a variant")
    }

    @Test
    fun `data object variant is reachable from the sealed parent`() {
        // Cancel is a `data object` — no fields besides the type discriminator.
        val cancel = Action::class.constructFromMap(mapOf("type" to "Cancel"))
        assertEquals(Action.Cancel, cancel)
    }

    @Test
    fun `direct variant construct still works (regression safety)`() {
        // The pre-existing variant-class construct path must keep working.
        val v = Action.SendMessage::class.constructFromMap(
            mapOf("type" to "SendMessage", "text" to "x")
        )
        assertEquals(Action.SendMessage("x"), v)
    }
}
