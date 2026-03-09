package agents_engine.generation

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// ─── Test domain types ───────────────────────────────────────────────────────

@Generable("Distance measurement between two points")
data class Measurement(
    @Guide("Value in meters")
    val distance: Double,
    @Guide("Measurement label")
    val label: String,
)

@Generable
data class BareFields(
    val count: Int,
    val name: String,
)

@Generable
@LlmDescription("Custom hand-written description — ignores all auto-generation")
data class ManuallyDescribed(val x: Int)

@Generable
sealed interface Outcome {
    @Guide("Task completed successfully")
    data class Success(
        @Guide("Result payload as text")
        val payload: String,
    ) : Outcome

    @Guide("Task could not be completed")
    data class Failure(
        @Guide("Human-readable error message")
        val error: String,
    ) : Outcome
}

// ─── Tests ───────────────────────────────────────────────────────────────────

class GenerableLlmDescriptionTest {

    // ─── auto-generated: data class ──────────────────────────────────────────

    @Test
    fun `toLlmDescription contains class name`() {
        assertTrue("Measurement" in Measurement::class.toLlmDescription())
    }

    @Test
    fun `toLlmDescription contains @Generable description`() {
        assertTrue("Distance measurement between two points" in Measurement::class.toLlmDescription())
    }

    @Test
    fun `toLlmDescription without @Generable description omits description line`() {
        val desc = BareFields::class.toLlmDescription()
        // still has class name and fields, just no extra description prose
        assertTrue("BareFields" in desc)
        assertTrue("count" in desc)
    }

    @Test
    fun `toLlmDescription contains field names`() {
        val desc = Measurement::class.toLlmDescription()
        assertTrue("distance" in desc)
        assertTrue("label" in desc)
    }

    @Test
    fun `toLlmDescription contains type names`() {
        val desc = Measurement::class.toLlmDescription()
        assertTrue("Double" in desc)
        assertTrue("String" in desc)
    }

    @Test
    fun `toLlmDescription contains Guide descriptions`() {
        val desc = Measurement::class.toLlmDescription()
        assertTrue("Value in meters" in desc)
        assertTrue("Measurement label" in desc)
    }

    @Test
    fun `toLlmDescription without Guide annotations still lists fields and types`() {
        val desc = BareFields::class.toLlmDescription()
        assertTrue("count" in desc)
        assertTrue("name" in desc)
        assertTrue("Int" in desc)
        assertTrue("String" in desc)
    }

    // ─── @LlmDescription override ─────────────────────────────────────────────

    @Test
    fun `@LlmDescription override replaces auto-generated text`() {
        val desc = ManuallyDescribed::class.toLlmDescription()
        assertTrue("Custom hand-written description" in desc)
    }

    @Test
    fun `@LlmDescription override suppresses auto-generated field list`() {
        val desc = ManuallyDescribed::class.toLlmDescription()
        assertFalse("ManuallyDescribed" in desc)
        assertFalse("Int" in desc)
    }

    // ─── auto-generated: sealed interface ────────────────────────────────────

    @Test
    fun `toLlmDescription for sealed interface contains interface name`() {
        assertTrue("Outcome" in Outcome::class.toLlmDescription())
    }

    @Test
    fun `toLlmDescription for sealed interface contains all variant names`() {
        val desc = Outcome::class.toLlmDescription()
        assertTrue("Success" in desc)
        assertTrue("Failure" in desc)
    }

    @Test
    fun `toLlmDescription for sealed interface contains variant Guide descriptions`() {
        val desc = Outcome::class.toLlmDescription()
        assertTrue("Task completed successfully" in desc)
        assertTrue("Task could not be completed" in desc)
    }

    @Test
    fun `toLlmDescription for sealed interface contains field names and Guide descriptions`() {
        val desc = Outcome::class.toLlmDescription()
        assertTrue("payload" in desc)
        assertTrue("Result payload as text" in desc)
        assertTrue("error" in desc)
        assertTrue("Human-readable error message" in desc)
    }
}
