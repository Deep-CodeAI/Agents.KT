package agents_engine.model

import agents_engine.core.agent
import agents_engine.generation.Generable
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

@Generable("Annotated args") data class GoodArgs(val foo: String)
data class BadArgs(val foo: String)   // intentionally NOT @Generable

@Generable("sealed root")
sealed interface SealedArgs {
    @Generable data class A(val x: String) : SealedArgs
    @Generable data class B(val y: Int) : SealedArgs
}

/**
 * Tests for #660 — typed tool builder rejects non-@Generable Args at agent construction.
 */
class GenerableEnforcementTest {

    @Test
    fun `typed tool with non-Generable Args throws at agent construction`() {
        try {
            agent<String, String>("a") {
                lateinit var doStuff: Tool<BadArgs, String>
                tools { doStuff = tool<BadArgs, String>("doStuff", "") { "ok" } }
                skills { skill<String, String>("s", "stub") { tools(doStuff) } }
            }
            fail("expected rejection of non-@Generable Args")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("BadArgs"), "error must name the offending type: ${e.message}")
            assertTrue(e.message!!.contains("doStuff"), "error must name the offending tool: ${e.message}")
            assertTrue(e.message!!.contains("@Generable"), "error must mention the missing annotation: ${e.message}")
        }
    }

    @Test
    fun `typed tool with @Generable Args works (regression)`() {
        val a = agent<String, String>("ok") {
            lateinit var doStuff: Tool<GoodArgs, String>
            tools { doStuff = tool<GoodArgs, String>("doStuff", "") { args -> args.foo } }
            skills { skill<String, String>("s", "stub") { tools(doStuff) } }
        }
        @Suppress("UNCHECKED_CAST")
        val result = a.toolMap["doStuff"]!!.executor(mapOf("foo" to "x"))
        assertTrue(result == "x")
    }

    @Test
    fun `typed tool with sealed Args is rejected at construction (#670)`() {
        try {
            agent<String, String>("a") {
                lateinit var doStuff: Tool<SealedArgs, String>
                tools { doStuff = tool<SealedArgs, String>("doStuff", "") { _ -> "ok" } }
                skills { skill<String, String>("s", "stub") { tools(doStuff) } }
            }
            fail("expected rejection of sealed Args")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("SealedArgs"), "error must name the offending type: ${e.message}")
            assertTrue(e.message!!.contains("sealed", ignoreCase = true), "error must explain why: ${e.message}")
            assertTrue(e.message!!.contains("doStuff"), "error must name the tool: ${e.message}")
        }
    }

    @Test
    fun `typed tool with concrete sealed VARIANT works (regression)`() {
        // SealedArgs.A is a concrete @Generable data class — only its sealed parent
        // is the disallowed shape.
        val a = agent<String, String>("ok") {
            lateinit var doVariant: Tool<SealedArgs.A, String>
            tools { doVariant = tool<SealedArgs.A, String>("doVariant", "") { args -> args.x } }
            skills { skill<String, String>("s", "stub") { tools(doVariant) } }
        }
        val result = a.toolMap["doVariant"]!!.executor(mapOf("x" to "v"))
        assertTrue(result == "v")
    }
}
