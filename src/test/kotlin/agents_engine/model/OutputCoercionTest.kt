package agents_engine.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * #3376 batch 1 — pins the output-coercion contracts extracted out of `AgenticLoop`'s private
 * `parseOutput` / `coerceSubstituteOutput` into [OutputCoercion]. Previously untestable (private to
 * the loop); behavior must match the prior inline fns.
 */
class OutputCoercionTest {

    @Test
    fun `parseOutput returns the text verbatim for a String output type`() {
        assertEquals("hi there", OutputCoercion.parseOutput("hi there", String::class))
    }

    @Test
    fun `coerceSubstituteOutput passes through a result already of the output type`() {
        assertEquals("done", OutputCoercion.coerceSubstituteOutput("done", String::class))
    }
}
