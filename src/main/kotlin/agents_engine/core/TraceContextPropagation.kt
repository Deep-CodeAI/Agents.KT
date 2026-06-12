package agents_engine.core

/**
 * `agents_engine/core/TraceContextPropagation.kt` — #3873 (slice 1). The
 * pluggable W3C trace-context seam: the core runtime injects whatever
 * headers the installed propagator supplies into outbound MCP/A2A HTTP
 * requests, and hands inbound headers back before dispatch. No-op by
 * default — zero overhead and zero OTel dependency in core; install the
 * OTel wiring from `:agents-kt-otel` (`OtelTracePropagation.install()`)
 * to carry `traceparent`/`tracestate` across process boundaries.
 *
 * Distributed traces then connect at the A2A/MCP seams instead of
 * starting fresh in every process — the root-cause-analysis story for
 * multi-process agent deployments.
 */
object TraceContextPropagation {

    @Volatile
    private var injector: () -> Map<String, String> = { emptyMap() }

    @Volatile
    private var extractor: (headers: Map<String, String>) -> AutoCloseable = { NOOP_SCOPE }

    /**
     * Install a propagator pair. [inject] returns the headers to add to
     * outbound requests (e.g. `traceparent` from the current span);
     * [extract] receives inbound headers and returns a scope that makes
     * the remote context current until closed.
     */
    fun install(
        inject: () -> Map<String, String>,
        extract: (headers: Map<String, String>) -> AutoCloseable,
    ) {
        injector = inject
        extractor = extract
    }

    /** Revert to the no-op default (tests). */
    fun reset() {
        injector = { emptyMap() }
        extractor = { NOOP_SCOPE }
    }

    /** Headers to add to an outbound cross-process call. */
    fun outboundHeaders(): Map<String, String> = injector()

    /** Make an inbound request's remote context current for the returned scope. */
    fun withInbound(headers: Map<String, String>): AutoCloseable = extractor(headers)

    private val NOOP_SCOPE = AutoCloseable { }
}
