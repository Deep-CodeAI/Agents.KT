package agents_engine.core

import agents_engine.runtime.events.AgentEvent
import agents_engine.runtime.events.session
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #2720 — pins the native attribution surface on [AgentRuntimeContext].
 * Closes the downstream side-channel pattern tracked in project #21 #2719.
 */
class AttributionTest {

    @Test
    fun `default AgentRuntimeContext carries empty attribution and null typed accessors`() {
        val ctx = AgentRuntimeContext()
        assertEquals(emptyMap(), ctx.attribution)
        assertNull(ctx.userId)
        assertNull(ctx.projectId)
        assertNull(ctx.dialogId)
    }

    @Test
    fun `typed accessors read from the canonical AttributionKeys constants`() {
        val ctx = AgentRuntimeContext(
            attribution = mapOf(
                AttributionKeys.USER_ID to "u-42",
                AttributionKeys.PROJECT_ID to "p-1",
                AttributionKeys.DIALOG_ID to "d-7",
            ),
        )
        assertEquals("u-42", ctx.userId)
        assertEquals("p-1", ctx.projectId)
        assertEquals("d-7", ctx.dialogId)
    }

    @Test
    fun `arbitrary attribution keys round-trip without typed accessor`() {
        val ctx = AgentRuntimeContext(
            attribution = mapOf("tenantId" to "acme", "keyOwner" to "alice"),
        )
        assertEquals("acme", ctx.attribution["tenantId"])
        assertEquals("alice", ctx.attribution["keyOwner"])
        assertNull(ctx.userId, "non-canonical keys do NOT populate the typed accessors")
    }

    @Test
    fun `copy semantics work — attribution can be extended without rebuilding the context`() {
        val original = AgentRuntimeContext(
            sessionId = "s-1",
            attribution = mapOf(AttributionKeys.USER_ID to "u-1"),
        )
        val extended = original.copy(
            attribution = original.attribution + (AttributionKeys.PROJECT_ID to "p-9"),
        )
        // Original untouched.
        assertEquals(mapOf(AttributionKeys.USER_ID to "u-1"), original.attribution)
        // Extended carries both.
        assertEquals("u-1", extended.userId)
        assertEquals("p-9", extended.projectId)
        assertEquals("s-1", extended.sessionId, "sessionId survives the copy")
    }

    @Test
    fun `attribution propagates through withAgentRuntimeContext to nested currentOrNew lookups`() {
        runBlocking {
            withAgentRuntimeContext(
                AgentRuntimeContext.currentOrNew().copy(
                    attribution = mapOf(
                        AttributionKeys.USER_ID to "u-alice",
                        AttributionKeys.DIALOG_ID to "dialog-99",
                    ),
                ),
            ) {
                // Nested lookups inside the block see the threaded context.
                val nested = AgentRuntimeContext.currentOrNew()
                assertEquals("u-alice", nested.userId)
                assertEquals("dialog-99", nested.dialogId)
            }

            // After the block returns, the ThreadLocal is restored to the
            // previous value (null here — no outer context was set).
            val outerCtx = AgentRuntimeContext.currentOrNew()
            assertNull(outerCtx.userId, "ThreadLocal must be restored after the block")
        }
    }

    @Test
    fun `attribution surfaces on AgentEvents emitted from inside the wrapped scope`() {
        // The bridge consumption pattern: deployer wraps the agent invocation
        // in withAgentRuntimeContext carrying attribution, then collects
        // session events. Every event's runtimeContext (and the convenience
        // accessors on AgentEvent) carries the attribution.
        val a = agent<String, String>("BridgeConsumer") {
            skills {
                skill<String, String>("echo", "echo") {
                    implementedBy { it }
                }
            }
        }

        val events = runBlocking {
            withAgentRuntimeContext(
                AgentRuntimeContext.currentOrNew().copy(
                    attribution = mapOf(
                        AttributionKeys.USER_ID to "u-bridge",
                        AttributionKeys.PROJECT_ID to "p-bridge",
                        AttributionKeys.DIALOG_ID to "d-bridge",
                        "tenantId" to "acme",
                    ),
                ),
            ) {
                a.session("ping").events.toList()
            }
        }

        // The non-agentic implementedBy path emits SkillStarted + SkillCompleted
        // + Completed; assert ALL of them carry the attribution. This is the
        // contract bridges rely on — no event escapes without attribution if
        // the outer scope was wrapped.
        assertTrue(events.isNotEmpty(), "session must emit events")
        for (event in events) {
            assertEquals("u-bridge", event.userId, "userId missing on ${event::class.simpleName}")
            assertEquals("p-bridge", event.projectId, "projectId missing on ${event::class.simpleName}")
            assertEquals("d-bridge", event.dialogId, "dialogId missing on ${event::class.simpleName}")
            assertEquals(
                "acme", event.attribution["tenantId"],
                "non-canonical attribution key missing on ${event::class.simpleName}",
            )
        }

        // The final Completed event surfaces the typed output too.
        val completed = events.filterIsInstance<AgentEvent.Completed<String>>().single()
        assertEquals("ping", completed.output)
    }

    @Test
    fun `equals + hashCode include attribution`() {
        val a = AgentRuntimeContext(
            requestId = "r-1",
            attribution = mapOf(AttributionKeys.USER_ID to "u-1"),
        )
        val b = AgentRuntimeContext(
            requestId = "r-1",
            attribution = mapOf(AttributionKeys.USER_ID to "u-1"),
        )
        val c = AgentRuntimeContext(
            requestId = "r-1",
            attribution = mapOf(AttributionKeys.USER_ID to "u-2"),
        )
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertTrue(a != c, "different attribution → unequal contexts")
    }
}
