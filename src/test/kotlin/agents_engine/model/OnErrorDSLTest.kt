package agents_engine.model

import agents_engine.core.agent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OnErrorDSLTest {

    // -- invalidArgs: deterministic fix --

    @Test
    fun `invalidArgs fix lambda receives raw args and returns fixed string`() {
        var receivedRaw: String? = null
        var receivedError: String? = null

        val a = agent<String, String>("a") {
            tools {
                tool("greet", "Greet") { args -> "Hi ${args["name"]}" }
            }
            onToolError("greet") {
                invalidArgs { raw, error ->
                    receivedRaw = raw
                    receivedError = error
                    fix { """{"name":"world"}""" }
                }
            }
            skills { skill<String, String>("s", "s") { implementedBy { it } } }
        }

        assertNotNull(a.getToolErrorHandler("greet"))
    }

    @Test
    fun `invalidArgs fix lambda returning null signals cannot-fix`() {
        val handler = OnErrorBuilder().apply {
            invalidArgs { _, _ -> fix { null } }
        }.build()

        val result = handler.handleInvalidArgs("garbage", "parse error")
        assertIs<RepairResult.Unrecoverable>(result)
    }

    @Test
    fun `invalidArgs fix lambda returning value signals fixed`() {
        val handler = OnErrorBuilder().apply {
            invalidArgs { raw, _ -> fix { raw.replace(",}", "}") } }
        }.build()

        val result = handler.handleInvalidArgs("""{"a":1,}""", "trailing comma")
        assertIs<RepairResult.Fixed>(result)
        assertEquals("""{"a":1}""", result.value)
    }

    // -- deserializationError: deterministic sanitize --

    @Test
    fun `deserializationError sanitize lambda fixes raw value`() {
        val handler = OnErrorBuilder().apply {
            deserializationError { raw, _ -> sanitize { raw.replace("\\", "/") } }
        }.build()

        val result = handler.handleDeserializationError("C:\\Users\\file", "Expected unix path")
        assertIs<RepairResult.Fixed>(result)
        assertEquals("C:/Users/file", result.value)
    }

    @Test
    fun `deserializationError sanitize returning null signals cannot-fix`() {
        val handler = OnErrorBuilder().apply {
            deserializationError { _, _ -> sanitize { null } }
        }.build()

        val result = handler.handleDeserializationError("binary-garbage", "Not decodable")
        assertIs<RepairResult.Unrecoverable>(result)
    }

    // -- executionError: retry --

    @Test
    fun `executionError retry returns Retry with max attempts and backoff`() {
        val handler = OnErrorBuilder().apply {
            executionError { _ -> retry(maxAttempts = 3) }
        }.build()

        val result = handler.handleExecutionError(RuntimeException("timeout"))
        assertIs<RepairResult.Retry>(result)
        assertEquals(3, result.maxAttempts)
    }

    @Test
    fun `executionError with no handler returns null`() {
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
