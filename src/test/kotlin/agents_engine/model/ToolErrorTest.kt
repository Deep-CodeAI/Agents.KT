package agents_engine.model

import kotlin.reflect.typeOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ToolErrorTest {

    @Test
    fun `InvalidArgs carries raw args, parse error, and schema`() {
        val error = ToolError.InvalidArgs(
            rawArgs = """{"name": "world",}""",
            parseError = "Trailing comma",
            expectedSchema = mapOf("name" to "String"),
        )
        assertIs<ToolError>(error)
        assertEquals("""{"name": "world",}""", error.rawArgs)
        assertEquals("Trailing comma", error.parseError)
    }

    @Test
    fun `DeserializationError carries raw value, target type, and cause`() {
        val cause = IllegalArgumentException("Not a valid path")
        val error = ToolError.DeserializationError(
            rawValue = "C:\\Users\\file.txt",
            targetType = typeOf<String>(),
            cause = cause,
        )
        assertIs<ToolError>(error)
        assertEquals("C:\\Users\\file.txt", error.rawValue)
        assertEquals(cause, error.cause)
    }

    @Test
    fun `ExecutionError carries args and cause`() {
        val cause = RuntimeException("Connection timeout")
        val error = ToolError.ExecutionError(
            args = mapOf("url" to "http://example.com"),
            cause = cause,
        )
        assertIs<ToolError>(error)
        assertEquals("http://example.com", error.args["url"])
        assertEquals(cause, error.cause)
    }

    @Test
    fun `EscalationError carries source, reason, severity, original error, and attempts`() {
        val original = ToolError.InvalidArgs("{", "Unexpected EOF", emptyMap())
        val error = ToolError.EscalationError(
            source = "json-fixer",
            reason = "Schema mismatch, not formatting",
            severity = Severity.HIGH,
            originalError = original,
            attempts = 3,
        )
        assertIs<ToolError>(error)
        assertEquals("json-fixer", error.source)
        assertEquals(Severity.HIGH, error.severity)
        assertEquals(3, error.attempts)
        assertIs<ToolError.InvalidArgs>(error.originalError)
    }

    @Test
    fun `Severity enum has four levels`() {
        val levels = Severity.entries
        assertEquals(4, levels.size)
        assertTrue(levels.map { it.name }.containsAll(listOf("LOW", "MEDIUM", "HIGH", "CRITICAL")))
    }

    @Test
    fun `ToolError subtypes are exhaustive in when expression`() {
        val errors: List<ToolError> = listOf(
            ToolError.InvalidArgs("x", "e", emptyMap()),
            ToolError.DeserializationError("x", typeOf<String>(), Exception()),
            ToolError.ExecutionError(emptyMap(), Exception()),
            ToolError.EscalationError("src", "reason", Severity.LOW, ToolError.InvalidArgs("x", "e", emptyMap()), 1),
        )
        for (error in errors) {
            val label = when (error) {
                is ToolError.InvalidArgs -> "invalid"
                is ToolError.DeserializationError -> "deser"
                is ToolError.ExecutionError -> "exec"
                is ToolError.EscalationError -> "escalation"
            }
            assertTrue(label.isNotEmpty())
        }
    }
}
