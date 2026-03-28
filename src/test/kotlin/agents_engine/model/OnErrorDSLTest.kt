package agents_engine.model

import agents_engine.core.agent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class OnErrorDSLTest {

    // -- invalidArgs: deterministic agent fix --

    @Test
    fun `invalidArgs fix with agent is accessible via getToolErrorHandler`() {
        val fixer = agent<String, String>("fixer") {
            skills { skill<String, String>("fix", "Fix") {
                implementedBy { _ -> """{"name":"world"}""" }
            }}
        }

        val a = agent<String, String>("a") {
            tools {
                tool("greet", "Greet") { args -> "Hi ${args["name"]}" }
            }
            onToolError("greet") {
                invalidArgs { _, _ -> fix(agent = fixer) }
            }
            skills { skill<String, String>("s", "s") { implementedBy { it } } }
        }

        assertNotNull(a.getToolErrorHandler("greet"))
    }

    @Test
    fun `invalidArgs fix with agent returns fixed value`() {
        val fixer = agent<String, String>("trailing-comma-fixer") {
            skills { skill<String, String>("fix", "Fix trailing commas") {
                implementedBy { input -> input.replace(",}", "}") }
            }}
        }

        val handler = OnErrorBuilder().apply {
            invalidArgs { _, _ -> fix(agent = fixer) }
        }.build()

        val result = handler.handleInvalidArgs("""{"a":1,}""", "trailing comma")
        assertIs<RepairResult.Fixed>(result)
        assertEquals("""{"a":1}""", result.value)
    }

    @Test
    fun `invalidArgs fix with failing agent returns unrecoverable`() {
        val broken = agent<String, String>("broken") {
            skills { skill<String, String>("fix", "Fix") {
                implementedBy { _ -> error("Cannot fix") }
            }}
        }

        val handler = OnErrorBuilder().apply {
            invalidArgs { _, _ -> fix(agent = broken) }
        }.build()

        val result = handler.handleInvalidArgs("garbage", "parse error")
        assertIs<RepairResult.Unrecoverable>(result)
    }

    // -- deserializationError: deterministic agent sanitize --

    @Test
    fun `deserializationError sanitize with agent fixes raw value`() {
        val pathFixer = agent<String, String>("path-fixer") {
            skills { skill<String, String>("fix", "Normalize paths") {
                implementedBy { input -> input.replace("\\", "/") }
            }}
        }

        val handler = OnErrorBuilder().apply {
            deserializationError { _, _ -> sanitize(agent = pathFixer) }
        }.build()

        val result = handler.handleDeserializationError("C:\\Users\\file", "Expected unix path")
        assertIs<RepairResult.Fixed>(result)
        assertEquals("C:/Users/file", result.value)
    }

    @Test
    fun `deserializationError sanitize with failing agent returns unrecoverable`() {
        val broken = agent<String, String>("broken") {
            skills { skill<String, String>("fix", "Fix") {
                implementedBy { _ -> error("Cannot sanitize") }
            }}
        }

        val handler = OnErrorBuilder().apply {
            deserializationError { _, _ -> sanitize(agent = broken) }
        }.build()

        val result = handler.handleDeserializationError("binary-garbage", "Not decodable")
        assertIs<RepairResult.Unrecoverable>(result)
    }

    // -- executionError: retry --

    @Test
    fun `executionError retry returns Retry with max attempts`() {
        val handler = OnErrorBuilder().apply {
            executionError { _ -> retry(maxAttempts = 3) }
        }.build()

        val result = handler.handleExecutionError(RuntimeException("timeout"))
        assertIs<RepairResult.Retry>(result)
        assertEquals(3, result.maxAttempts)
    }

    @Test
    fun `executionError with null return passes through`() {
        val handler = OnErrorBuilder().apply {
            executionError { _ -> null }
        }.build()

        val result = handler.handleExecutionError(RuntimeException("OOM"))
        assertNull(result)
    }

    // -- No handler returns null --

    @Test
    fun `unset invalidArgs handler returns null`() {
        val handler = OnErrorBuilder().build()
        assertNull(handler.handleInvalidArgs("raw", "error"))
    }

    @Test
    fun `unset deserializationError handler returns null`() {
        val handler = OnErrorBuilder().build()
        assertNull(handler.handleDeserializationError("raw", "error"))
    }

    @Test
    fun `unset executionError handler returns null`() {
        val handler = OnErrorBuilder().build()
        assertNull(handler.handleExecutionError(RuntimeException("fail")))
    }
}
