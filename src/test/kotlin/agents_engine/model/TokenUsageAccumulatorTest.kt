package agents_engine.model

import agents_engine.core.SessionSnapshot
import agents_engine.core.SnapshotJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * #2867 — regression coverage for the `TokenUsage` cumulative accumulator
 * and snapshot round-trip.
 *
 * Pre-#2867:
 *   - The AgenticLoop's inline merge built `TokenUsage(...)` without
 *     `reasoningTokens`, so the cumulative count silently lost reasoning
 *     billing on multi-turn reasoning streams.
 *   - The `SnapshotJson.encodeTokens` writer omitted `cacheWriteTokens`,
 *     and the decoder didn't read it back. Snapshot/resume across
 *     Anthropic prompt-caching turns lost the premium-rate billing
 *     evidence the audit story relies on.
 *
 * The fix:
 *   - One `TokenUsage.plus` operator owns the merge. All five fields
 *     (prompt, completion, cached, cacheWrite, reasoning) accumulate.
 *     Nullable fields stay null only when BOTH operands are null.
 *   - Snapshot codec adds `cacheWrite` to both encode and decode; pre-
 *     #2867 snapshots decode with `cacheWriteTokens = null` (back-compat).
 */
class TokenUsageAccumulatorTest {

    @Test
    fun `plus sums every field including reasoning and cacheWrite`() {
        val a = TokenUsage(
            promptTokens = 100,
            completionTokens = 50,
            cachedInputTokens = 30,
            cacheWriteTokens = 20,
            reasoningTokens = 40,
            provider = "anthropic",
            model = "claude-opus-4-7",
        )
        val b = TokenUsage(
            promptTokens = 200,
            completionTokens = 70,
            cachedInputTokens = 90,
            cacheWriteTokens = 10,
            reasoningTokens = 60,
            provider = "anthropic",
            model = "claude-opus-4-7",
        )

        val sum = a + b
        assertEquals(300, sum.promptTokens)
        assertEquals(120, sum.completionTokens)
        assertEquals(120, sum.cachedInputTokens)
        assertEquals(30, sum.cacheWriteTokens)
        assertEquals(100, sum.reasoningTokens)
        assertEquals("anthropic", sum.provider, "provider follows the latest operand")
    }

    @Test
    fun `plus keeps a nullable field null only when both sides are null`() {
        val left = TokenUsage(promptTokens = 10, completionTokens = 5)  // all nullables null
        val right = TokenUsage(
            promptTokens = 20,
            completionTokens = 5,
            cachedInputTokens = 7,
            cacheWriteTokens = 3,
            reasoningTokens = 2,
        )

        val sum = left + right
        // A turn that reports a non-null value contributes to the running
        // total — a prior null is treated as zero, not as "erase".
        assertEquals(7, sum.cachedInputTokens)
        assertEquals(3, sum.cacheWriteTokens)
        assertEquals(2, sum.reasoningTokens)

        // Both null → still null (no over-report of zero).
        val bothNull = left + TokenUsage(promptTokens = 1, completionTokens = 1)
        assertNull(bothNull.cachedInputTokens)
        assertNull(bothNull.cacheWriteTokens)
        assertNull(bothNull.reasoningTokens)
    }

    @Test
    fun `snapshot round-trip preserves cacheWriteTokens — pre-fix dropped it`() {
        val usage = TokenUsage(
            promptTokens = 1000,
            completionTokens = 500,
            cachedInputTokens = 300,
            cacheWriteTokens = 200,
            reasoningTokens = 400,
            provider = "anthropic",
            model = "claude-opus-4-7",
        )

        val snap = SessionSnapshot(
            messages = emptyList(),
            turns = 1,
            toolCalls = 0,
            toolCallLimit = 0,
            tokensUsed = usage,
            memory = emptyMap(),
            requestId = "tu-1",
            sessionId = null,
            manifestHash = null,
        )

        val round = SnapshotJson.decode(SnapshotJson.encode(snap))
        val decoded = round.tokensUsed
        assertNotNull(decoded)
        assertEquals(1000, decoded.promptTokens)
        assertEquals(500, decoded.completionTokens)
        assertEquals(300, decoded.cachedInputTokens)
        assertEquals(200, decoded.cacheWriteTokens, "cacheWriteTokens was dropped pre-#2867")
        assertEquals(400, decoded.reasoningTokens)
        assertEquals("anthropic", decoded.provider)
        assertEquals("claude-opus-4-7", decoded.model)
    }

    @Test
    fun `pre-2867 snapshots decode with null cacheWriteTokens — back-compat`() {
        // Hand-crafted legacy snapshot — the `cacheWrite` key wasn't part of
        // the wire shape before #2867. Decoding must not crash; the field
        // surfaces as null.
        val legacy = """{
            "requestId":"legacy-1","sessionId":null,"manifestHash":null,
            "pendingInterruptCallId":null,
            "turns":1,"toolCalls":0,"toolCallLimit":0,
            "tokens":{
                "prompt":100,"completion":50,
                "cached":20,"reasoning":30,
                "provider":"anthropic","model":"claude-opus-4-7"
            },
            "memory":{},"messages":[]
        }""".trimIndent()

        val snap = SnapshotJson.decode(legacy)
        val usage = snap.tokensUsed
        assertNotNull(usage)
        assertEquals(100, usage.promptTokens)
        assertEquals(50, usage.completionTokens)
        assertEquals(20, usage.cachedInputTokens)
        assertEquals(30, usage.reasoningTokens)
        assertNull(usage.cacheWriteTokens, "legacy snapshots have no cacheWrite key")
    }
}
