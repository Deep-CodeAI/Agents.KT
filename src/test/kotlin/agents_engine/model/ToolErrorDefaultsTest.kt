package agents_engine.model

import agents_engine.core.agent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ToolErrorDefaultsTest {

    private val trailingCommaFixer = agent<String, String>("trailing-comma-fixer") {
        skills { skill<String, String>("fix", "Fix trailing commas") {
            implementedBy { input -> input.replace(",}", "}") }
        }}
    }

    private val defaultFixer = agent<String, String>("default-fixer") {
        skills { skill<String, String>("fix", "Default fix") {
            implementedBy { _ -> "default-fix" }
        }}
    }

    private val compileFixer = agent<String, String>("compile-fixer") {
        skills { skill<String, String>("fix", "Compile fix") {
            implementedBy { _ -> "compile-fix" }
        }}
    }

    @Test
    fun `defaults onError applies to all tools`() {
        val a = agent<String, String>("a") {
            tools {
                defaults {
                    onError {
                        invalidArgs { _, _ -> fix(agent = trailingCommaFixer) }
                    }
                }
                tool("greet", "Greet") { args -> "Hi ${args["name"]}" }
                tool("farewell", "Farewell") { args -> "Bye ${args["name"]}" }
            }
            skills { skill<String, String>("s", "s") { implementedBy { it } } }
        }

        assertNotNull(a.getToolErrorHandler("greet"))
        assertNotNull(a.getToolErrorHandler("farewell"))
    }

    @Test
    fun `per-tool onError overrides defaults`() {
        val a = agent<String, String>("a") {
            tools {
                defaults {
                    onError {
                        invalidArgs { _, _ -> fix(agent = defaultFixer) }
                    }
                }
                tool("greet", "Greet") { args -> "Hi ${args["name"]}" }
                tool("compile", "Compile") { _ -> "ok" }
            }
            onToolError("compile") {
                invalidArgs { _, _ -> fix(agent = compileFixer) }
            }
            skills { skill<String, String>("s", "s") { implementedBy { it } } }
        }

        // greet uses default
        val greetHandler = a.getToolErrorHandler("greet")!!
        val greetResult = greetHandler.handleInvalidArgs("bad", "error")
        assertIs<RepairResult.Fixed>(greetResult)
        assertEquals("default-fix", greetResult.value)

        // compile uses override
        val compileHandler = a.getToolErrorHandler("compile")!!
        val compileResult = compileHandler.handleInvalidArgs("bad", "error")
        assertIs<RepairResult.Fixed>(compileResult)
        assertEquals("compile-fix", compileResult.value)
    }

    @Test
    fun `tool without defaults or override has no handler`() {
        val a = agent<String, String>("a") {
            tools {
                tool("greet", "Greet") { _ -> "Hi" }
            }
            skills { skill<String, String>("s", "s") { implementedBy { it } } }
        }

        assertNull(a.getToolErrorHandler("greet"))
    }
}
