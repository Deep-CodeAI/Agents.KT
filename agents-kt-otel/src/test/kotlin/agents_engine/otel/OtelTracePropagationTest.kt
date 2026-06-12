package agents_engine.otel

import agents_engine.core.TraceContextPropagation
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.Context
import io.opentelemetry.sdk.trace.SdkTracerProvider
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// #3873 slice 1 — OTel wiring: inject carries the current span's traceparent;
// extract makes the remote context current for the scope.

class OtelTracePropagationTest {

    @AfterTest
    fun reset() = TraceContextPropagation.reset()

    @Test
    fun `inject emits traceparent for the current span and extract restores it`() {
        val tracer = SdkTracerProvider.builder().build().get("test")
        OtelTracePropagation.install(W3CTraceContextPropagator.getInstance())

        val span = tracer.spanBuilder("outer").startSpan()
        val headers = span.makeCurrent().use { TraceContextPropagation.outboundHeaders() }
        span.end()

        val traceparent = headers["traceparent"] ?: error("traceparent must be injected; got: $headers")
        assertTrue(span.spanContext.traceId in traceparent, "traceparent carries the live trace id")

        // Extraction round-trip: the remote context becomes current inside the scope.
        TraceContextPropagation.withInbound(mapOf("traceparent" to traceparent)).use {
            val current = io.opentelemetry.api.trace.Span.fromContext(Context.current())
            assertEquals(span.spanContext.traceId, current.spanContext.traceId)
        }
    }
}
