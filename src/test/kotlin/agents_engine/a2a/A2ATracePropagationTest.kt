package agents_engine.a2a

import agents_engine.core.TraceContextPropagation
import agents_engine.core.agent
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

// #3873 slice 1 — the W3C propagation seam carries headers across the A2A
// boundary: outbound injection on the client, inbound extraction scoped
// around the server dispatch. (OTel-specific wiring is tested in
// :agents-kt-otel; here a fake propagator proves the seam itself.)

class A2ATracePropagationTest {

    @AfterTest
    fun reset() = TraceContextPropagation.reset()

    @Test
    fun `traceparent injected by the client arrives in the server's inbound scope`() {
        val seenInbound = AtomicReference<Map<String, String>>(emptyMap())
        TraceContextPropagation.install(
            inject = { mapOf("traceparent" to "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01") },
            extract = { headers -> seenInbound.set(headers); AutoCloseable { } },
        )

        val echo = agent<String, String>("echo") {
            skills { skill<String, String>("echo", "Echoes") { implementedBy { "ok: $it" } } }
        }
        val server = A2AServer.from(echo).start()
        try {
            val remote = a2aAgent<String, String>("remote", server.url)
            assertEquals("ok: hi", remote("hi"))
            assertEquals(
                "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01",
                seenInbound.get()["traceparent"],
                "server must hand the inbound traceparent to the propagator; got: ${seenInbound.get()}",
            )
        } finally {
            server.stop()
        }
    }

    @Test
    fun `without an installed propagator the seam is a no-op`() {
        val echo = agent<String, String>("plain-echo") {
            skills { skill<String, String>("echo", "Echoes") { implementedBy { "ok" } } }
        }
        val server = A2AServer.from(echo).start()
        try {
            assertEquals("ok", a2aAgent<String, String>("remote", server.url)("x"))
        } finally {
            server.stop()
        }
    }
}
