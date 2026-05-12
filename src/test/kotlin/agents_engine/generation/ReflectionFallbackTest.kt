package agents_engine.generation

import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for #1705 — Phase 3 reflection fallback graceful degradation.
 *
 * When `kotlin-reflect` is absent from the runtime classpath, our
 * reflection-using paths must catch `NoClassDefFoundError` and return
 * null instead of crashing. Caller responsibility then is to route
 * through the existing error / null-handling paths (typed-tool
 * deserialization returns null → onError.invalidArgs; schema lookup
 * returns null → falls back further or returns an error).
 *
 * We can't actually remove `kotlin-reflect` from the test classpath
 * (it's a transitive `testImplementation`), but we can pin the
 * try/catch wrap by checking the explicit `ReflectionFallback`
 * helpers — they expose a controllable seam where we can simulate
 * NCDFE via an injected lambda. If the implementation drops the
 * try/catch the test fails loudly.
 */
class ReflectionFallbackTest {

    @Test
    fun `withReflection returns the body's result on the happy path`() {
        val out = ReflectionFallback.withReflection { 42 }
        assertEquals(42, out)
    }

    @Test
    fun `withReflection returns null when the body throws NoClassDefFoundError`() {
        val out = ReflectionFallback.withReflection<Int> { throw NoClassDefFoundError("simulated absent kotlin-reflect") }
        assertNull(out, "the wrap must convert NCDFE to a null return; got non-null")
    }

    @Test
    fun `withReflection lets non-NCDFE exceptions propagate`() {
        var caught: Throwable? = null
        try {
            ReflectionFallback.withReflection<Int> { throw IllegalStateException("real bug, not a missing dep") }
        } catch (e: IllegalStateException) {
            caught = e
        }
        assertTrue(caught is IllegalStateException, "non-NCDFE must propagate so real bugs aren't swallowed")
    }

    @Test
    fun `withReflection also catches LinkageError parent of NCDFE`() {
        // LinkageError is NCDFE's parent. JDK has thrown LinkageError in
        // edge cases (ClassFormatError, IncompatibleClassChangeError).
        // Production: better to catch the parent and degrade.
        val out = ReflectionFallback.withReflection<Int> { throw LinkageError("simulated") }
        assertNull(out, "LinkageError should also gracefully degrade")
    }

    private fun assertEquals(expected: Int, actual: Int?) {
        kotlin.test.assertEquals(expected, actual)
    }
}
