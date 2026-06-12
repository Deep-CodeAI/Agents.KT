package agents_engine.otel

import agents_engine.core.TraceContextPropagation
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.TextMapGetter
import io.opentelemetry.context.propagation.TextMapPropagator
import io.opentelemetry.context.propagation.TextMapSetter

/**
 * `agents_engine/otel/OtelTracePropagation.kt` — #3873 (slice 1). Wires
 * the core [TraceContextPropagation] seam to OpenTelemetry's W3C
 * propagators: outbound MCP/A2A requests carry `traceparent`/
 * `tracestate` from the current span, and inbound requests make the
 * remote context current for the duration of the dispatch — so
 * distributed agent traces connect across process boundaries instead of
 * starting fresh.
 *
 * ```kotlin
 * OtelTracePropagation.install()                 // GlobalOpenTelemetry propagators
 * // or: OtelTracePropagation.install(openTelemetry.propagators.textMapPropagator)
 * ```
 */
object OtelTracePropagation {

    fun install(propagator: TextMapPropagator = GlobalOpenTelemetry.getPropagators().textMapPropagator) {
        TraceContextPropagation.install(
            inject = {
                val headers = mutableMapOf<String, String>()
                propagator.inject(Context.current(), headers, MapSetter)
                headers
            },
            extract = { headers ->
                val extracted = propagator.extract(Context.current(), headers, MapGetter)
                val scope = extracted.makeCurrent()
                AutoCloseable { scope.close() }
            },
        )
    }

    private object MapSetter : TextMapSetter<MutableMap<String, String>> {
        override fun set(carrier: MutableMap<String, String>?, key: String, value: String) {
            carrier?.put(key, value)
        }
    }

    private object MapGetter : TextMapGetter<Map<String, String>> {
        override fun keys(carrier: Map<String, String>): Iterable<String> = carrier.keys
        override fun get(carrier: Map<String, String>?, key: String): String? = carrier?.get(key.lowercase())
    }
}
