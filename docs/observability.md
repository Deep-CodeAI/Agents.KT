# Observability — JSONL audit logs + `ObservabilityBridge`

This page covers two layers:

- **Shipped:** `:agents-kt-observability` JSONL audit exporter (#1914), a zero-vendor-dependency on-disk log format for `PipelineEvent` and `AgentEvent` rows.
- **Shipped:** `ObservabilityBridge` + `Agent.observe(bridge)` in `:agents-kt-observability`, plus concrete adapters for OpenTelemetry (`:agents-kt-otel`, #1908), LangSmith (`:agents-kt-langsmith`, #1909), and Langfuse (`:agents-kt-langfuse`, #1910).

## JSONL audit exporter

The JSONL exporter writes one deterministic JSON object per event, one line at a time. It is intended for retained audit records, local debugging, and WORM/object-lock pipelines where a plain text format is easier to inspect than a tracing backend.

```kotlin
import agents_engine.observability.JsonlAuditExporter
import agents_engine.observability.JsonlRotation
import agents_engine.observability.events
import java.nio.file.Path

val agent = agent<String, String>("assistant") {
    skills {
        skill<String, String>("echo", "Echo input") {
            implementedBy { it }
        }
    }
}

val exporters = agent.events.export {
    jsonl(
        file("/var/log/agents-kt/audit.jsonl"),
        rotation = JsonlRotation.Size(maxBytes = 50L * 1024 * 1024),
    )
}

try {
    agent("hello")
} finally {
    exporters.forEach { it.close() }
}
```

Each row uses the same field set:

```text
requestId, sessionId, manifestHash, agentId, skillId, toolId, eventType,
timestamp, inputType, outputType, budgetState, guardrailDecision,
mcpClientId, toolPolicyRisk, usedDeclaredCapability, provider, model
```

The exporter deliberately does **not** serialize raw tool arguments, tool results, streamed text, generated output, or exception messages. It emits identifiers, event names, type names, and provider/model metadata so secret-like values do not leak into audit logs by default. `manifestHash` is populated when the runtime event carries one.
For `ToolCalled` rows, `toolPolicyRisk` mirrors the tool's declarative `ToolPolicy.risk`, and `usedDeclaredCapability` is true when the executed tool declares at least one filesystem/network/environment capability. Tool calls **blocked** by an `onBeforeToolCall` `Decision.Deny` surface as `ToolDenied` rows — `eventType` `ToolDenied`, the tool id, `guardrailDecision` `"Deny"`, and the same policy fields — so a security audit captures blocked attempts, not just executed ones (#2395). Consistent with the PII-safe default, the on-disk row records only the decision *type*; the human-readable denial reason stays off disk (it can embed arg values) and is available on the live `PipelineEvent.ToolDenied` and on the OTel/LangSmith/Langfuse spans. Denials reach the exporter via `Agent.observe { }`; `onToolUse` does not fire for a denied call.

You can also write streaming/session events directly:

```kotlin
val audit = JsonlAuditExporter(
    Path.of("/var/log/agents-kt/session.jsonl"),
    rotation = JsonlRotation.Daily(),
)

agent.session(input).events.collect { event ->
    audit.write(event)
}
```

Line-buffered writes append synchronously. `SkillCompleted` and `Failed` events trigger a flush attempt of any buffered lines. If the filesystem rejects writes (for example disk full), the exporter catches the failure, buffers up to `maxBufferedLines`, drops the oldest line under sustained backpressure, logs the drop through the configured logger, and never throws into the agent path.

Useful shell checks:

```bash
jq -c 'select(.eventType == "ToolCalled") | {requestId, agentId, toolId}' audit.jsonl
jq -s 'group_by(.eventType) | map({eventType: .[0].eventType, count: length})' audit.jsonl
tail -f audit.jsonl | jq -r '[.timestamp, .requestId, .agentId, .eventType] | @tsv'
```

## Why a bridge contract

The framework has the **right shape** for observability — `PipelineEvent` (post-hoc sealed type via `Agent.observe`) plus `AgentEvent<OUT>` (cold `Flow` from `agent.session()`) — and the JSONL exporter now gives those events a canonical on-disk record. The bridge layer adds vendor tracing without forcing every adopter who wants OpenTelemetry / LangSmith / Langfuse traces to write the same listener-to-span translation by hand.

Two design choices that fall out of the constraints:

1. **The contract lives in a separate module** (`:agents-kt-observability`) with zero vendor dependencies. A local-first Ollama user must not be forced to pull `io.opentelemetry:opentelemetry-api`.
2. **The contract ships AND validates against a real wire format simultaneously** (OTel GenAI semconv as the first adapter). Designing the contract in a vacuum produces a contract that doesn't quite fit when the first real adapter lands; designing both together avoids the re-cut.

LangSmith (#1909) and Langfuse (#1910) reuse the same contract.

## Contract

```kotlin
// In :agents-kt-observability (core types, no vendor deps)
interface ObservabilityBridge {
    fun onPipelineEvent(event: PipelineEvent)
    fun onAgentEvent(event: AgentEvent<*>)
    fun onInterceptorDecision(point: InterceptorPoint, decision: Decision<*>)
}

typealias InterceptorPoint = agents_engine.core.InterceptorPoint

fun <IN, OUT> Agent<IN, OUT>.observe(bridge: ObservabilityBridge): Agent<IN, OUT>
```

The `observe(bridge)` extension wires both event surfaces and the `onBefore*` interceptor decisions (#1907) into the bridge with one call. Existing `Agent.observe { event -> ... }` callers keep working — the bridge variant is additive.

## Adapter module structure

| Module | Purpose | Dependencies |
|---|---|---|
| `:agents-kt-observability` | The `ObservabilityBridge` interface + `Agent.observe(bridge)` extension | Zero vendor deps |
| `:agents-kt-otel` | OTel adapter (`OtelBridge(tracer)`) | `:agents-kt-observability` + `io.opentelemetry:opentelemetry-api:1.51.0` |
| `:agents-kt-langsmith` | LangSmith adapter (`LangSmithBridge(apiKey, project)`) | `:agents-kt-observability` + JDK `HttpClient` |
| `:agents-kt-langfuse` | Langfuse adapter (`LangfuseBridge(publicKey, secretKey, baseUrl)`) | `:agents-kt-observability` + JDK `HttpClient` |

Future vendor-specific adapter modules, if any, each pull only their own vendor dep and the shared contract.

**Hard constraint:** the root/core runtime classpath stays vendor-free; only adapter modules pull vendor APIs. `:agents-kt-langsmith` and `:agents-kt-langfuse` use the JDK HTTP client instead of vendor SDKs.

## OTel mapping

The OTel adapter maps to the **OpenTelemetry GenAI semantic conventions**:

| Source event | OTel artefact |
|---|---|
| `AgentEvent.SkillStarted` | Root span `agent.invoke` (or child if parent context present via `Context.current()`) |
| `AgentEvent.SkillCompleted` | Span end + attrs `gen_ai.usage.input_tokens`, `gen_ai.usage.output_tokens` from cumulative `TokenUsage` |
| `AgentEvent.ModelTurnStarted` / `ModelTurnCompleted` | Child span `gen_ai.chat`, attrs `gen_ai.operation.name=chat`, `gen_ai.system`, `gen_ai.request.model`, `gen_ai.request.temperature`, response type, and per-turn usage |
| `AgentEvent.Token` | `gen_ai.token` span event with token length only; token text is not recorded |
| `AgentEvent.ToolCallStarted` / `ToolCallFinished` | Child span `gen_ai.operation.name=tool`, attrs `tool.name`, `tool.call.id`, `tool.result.type`, `tool.error` |
| `AgentEvent.ToolCallArgumentsDelta` | `tool.arguments.delta` span event with delta length only; raw arguments are not recorded |
| `PipelineEvent.ErrorOccurred` | Span status `ERROR` + exception event with original throwable |
| `PipelineEvent.BudgetThreshold` | Span event `agent.budget.threshold` with reason and used-percent attrs |
| `PipelineEvent.ToolCalled` / `ToolDenied` / `KnowledgeLoaded` / `SkillChosen` | Span events on the active agent span (`ToolDenied` carries the denial reason — a blocked tool call, #2395) |
| Interceptor decisions | Span events `interceptor.proceed`, `interceptor.proceed_with`, `interceptor.deny`, `interceptor.substitute`; only the interceptor point is recorded |

Every event already carries `requestId`, `sessionId`, and `manifestHash`; bridge adapters propagate them as `agent.request.id`, `agent.session.id`, and `agent.manifest.hash` attributes when present.

The adapter intentionally records identifiers, type names, token lengths, and usage counts rather than raw prompts, streamed text, tool arguments, tool results, or interceptor denial reasons.

**Reasoning (#2406).** When `model { reasoning(...) }` is enabled, `AgentEvent.Reasoning` carries the model's thinking text on the live session stream. The tracing bridges record reasoning *length* only (`gen_ai.reasoning` / `llm.reasoning`, mirroring tokens), and the JSONL audit exporter omits reasoning entirely — it's high-volume and potentially sensitive, so it stays a live-stream signal rather than a persisted/traced one.

## Worked example

```kotlin
// In a Spring/Ktor service that already has an OTel SDK + exporter wired
import agents_engine.runtime.events.session

val tracer: Tracer = openTelemetry.getTracer("agents-kt-app")

val agent = agent<UserReq, AssistantReply>("assistant") {
    model { claude("claude-opus-4-7-20250514") }
    skills { /* ... */ }
}.observe(OtelBridge(tracer))      // <-- the wire-up

val reply = agent.session(req).await()
// → OTel exporter sees a tree of spans:
//   agent.invoke[assistant]
//     ├── gen_ai.chat gen_ai.request.model=claude-opus-4-7-20250514
//     ├── gen_ai.tool tool.name=searchKb
//     ├── gen_ai.chat gen_ai.request.model=claude-opus-4-7-20250514
//     └── gen_ai.tool tool.name=fetchTicket
```

Parent-context propagation: if the caller starts a span before `invoke`, the agent's root span is a child of it (via `Context.current()` — standard OTel idiom). Trace IDs propagate cleanly through composed pipelines.

## Verifying the contract

Tests use a deterministic recording `SpanExporter`:

1. **Bridge forwarding** — `observe(bridge)` forwards `PipelineEvent`, `AgentEvent`, and interceptor decisions while preserving existing observers.
2. **Single skill** — one `agent.invoke` span with request/session/manifest correlation and usage attrs.
3. **Model turn and tool call** — model turns produce `gen_ai.chat` child spans; `ToolCallStarted` / `ToolCallFinished` produce `gen_ai.tool` child spans.
4. **Error path** — failing skill surfaces `span.status = ERROR` + an exception event.
5. **Parent context propagation** — `tracer.spanBuilder("outer").startSpan()` before `invoke` -> the agent span has the outer span as parent.
6. **Interceptor denial** — `Decision.Deny` records `interceptor.deny` on the active span and marks it `ERROR`.

## LangSmith mapping

`LangSmithBridge(apiKey, project, baseUrl = "https://api.smith.langchain.com")` maps the same bridge events to LangSmith's run-tree model and dispatches them through the documented batch ingest endpoint.

| Source event | LangSmith run-tree artefact |
|---|---|
| `AgentEvent.SkillStarted` / `SkillCompleted` | Root `chain` run per skill invocation |
| `AgentEvent.ModelTurnStarted` / `ModelTurnCompleted` | Child `llm` run with provider/model/temperature inputs and token usage in `extra` |
| `AgentEvent.ToolCallStarted` / `ToolCallFinished` | Child `tool` run with `inputs.args`, `outputs.result`, and `error` on failed tool results |
| `AgentEvent.Failed` / `PipelineEvent.ErrorOccurred` | Active run `error` field plus `end_time` |
| `PipelineEvent.BudgetThreshold` | Active chain run `extra.budget` update |
| Interceptor decisions | Tags such as `interceptor:deny` / `interceptor:substitute` on the active run; pending decisions attach to fallback failure runs |

```kotlin
import agents_engine.langsmith.LangSmithBridge

val agent = agent<UserReq, AssistantReply>("assistant") {
    model { openai("gpt-4o-mini") }
    skills { /* ... */ }
}.observe(
    LangSmithBridge(
        apiKey = System.getenv("LANGSMITH_API_KEY"),
        project = "agents-kt-prod",
    ),
)
```

Dispatch is asynchronous: the bridge buffers run-create/run-update operations, sends them in batches, drops the oldest queued operation under sustained backpressure, logs failures, and never throws into the agent path. Tests use an in-memory recording sink and JSON fixture assertions; CI never calls LangSmith live.

## Langfuse mapping

`LangfuseBridge(publicKey, secretKey, baseUrl = "https://cloud.langfuse.com")` maps the same bridge events to Langfuse traces, generations, spans, and events. It posts batches to Langfuse's native ingestion endpoint (`/api/public/ingestion`) with Basic auth (`publicKey` as the username, `secretKey` as the password) and does not depend on the Langfuse JavaScript/Python SDKs.

| Source event | Langfuse artefact |
|---|---|
| `AgentEvent.SkillStarted` / `SkillCompleted` | `trace-create` for the trace start, then a same-id `trace-create` update with output and cumulative token usage metadata |
| `AgentEvent.ModelTurnStarted` / `ModelTurnCompleted` | `generation-create` / `generation-update` with provider, model, temperature, response type, `usage`, and `usageDetails` |
| `AgentEvent.Token` | `event-create` named `llm.token` with token length only |
| `AgentEvent.ToolCallStarted` / `ToolCallFinished` | `span-create` / `span-update` named `tool.<toolName>` with call id, parsed arguments, result type, result, and error level when applicable |
| `AgentEvent.ToolCallArgumentsDelta` | `event-create` named `tool.arguments.delta` with delta length only |
| `AgentEvent.Failed` / `PipelineEvent.ErrorOccurred` | Active trace output `status=failed`, error metadata, and `ERROR` level on still-open observations |
| `PipelineEvent.BudgetThreshold` / `ToolCalled` / `ToolDenied` / `KnowledgeLoaded` / `SkillChosen` | `event-create` observations on the active trace (`ToolDenied` = a blocked tool call with its reason, #2395) |
| Interceptor decisions | Tags such as `interceptor:deny` plus `event-create` named `interceptor.decision`; pending decisions attach to fallback failure traces |

```kotlin
import agents_engine.langfuse.LangfuseBridge

val agent = agent<UserReq, AssistantReply>("assistant") {
    model { openai("gpt-4o-mini") }
    skills { /* ... */ }
}.observe(
    LangfuseBridge(
        publicKey = System.getenv("LANGFUSE_PUBLIC_KEY"),
        secretKey = System.getenv("LANGFUSE_SECRET_KEY"),
        baseUrl = System.getenv("LANGFUSE_BASE_URL") ?: LangfuseBridge.DEFAULT_BASE_URL,
    ),
)
```

Dispatch is asynchronous: the bridge buffers ingestion events, sends them in batches, drops the oldest queued operation under sustained backpressure, logs failures, and never throws into the agent path. Tests use an in-memory recording sink and JSON fixture assertions; CI never calls Langfuse live.

## Sibling adapters

OTel, LangSmith, and Langfuse all sit behind the same `ObservabilityBridge` contract:

The shared contract means a switch from one vendor to another is one line: `.observe(OtelBridge(tracer))` → `.observe(LangSmithBridge(apiKey, project))` → `.observe(LangfuseBridge(publicKey, secretKey))`. No re-instrumentation.

## Phoenix and other open-source observability tools

Arize Phoenix, OpenLLMetry, and similar OSS observability stacks already consume OTel GenAI semconv. They get the `:agents-kt-otel` adapter for free — no separate module needed. Document the wiring pattern in this doc; no new module gets cut for them.

## What this does NOT do

- **Logging.** This is for traces / spans. Logs come from your existing logger; `onError` listener is the typical wire-up.
- **Metrics.** Counters / gauges / histograms are a separate concern. The bridge could emit OTel metrics too, but v1 ships traces only. Future expansion.
- **PII redaction.** The bridge passes through what the framework events carry. If args contain PII, redact in a listener BEFORE the bridge sees them — chain the listener: `agent.onToolUse { name, args, result -> redact(...) }.observe(bridge)`.

## Status

| Phase | What it ships |
|---|---|
| Shipped (#1914) | JSONL audit exporter in `:agents-kt-observability` |
| **Shipped (#1908)** | Bridge contract in `:agents-kt-observability`, `:agents-kt-otel`, and tests with a recording span exporter |
| **Shipped (#1909)** | `:agents-kt-langsmith`, async batch dispatch, backpressure logging, run-tree tests with a recording sink |
| **Shipped (#1910)** | `:agents-kt-langfuse`, native ingestion, async batch dispatch, backpressure logging, trace/span/generation tests with a recording sink |
| Future | Metrics emission, OpenLLMetry / Phoenix consumption guide |

The bridge consumes the shipped #1907 interceptor primitives, so adapters receive `onBeforeSkill`, `onBeforeToolCall`, and `onBeforeTurn` decisions without a second integration path.

## Related

- **[`docs/interceptors.md`](interceptors.md)** — `onBefore*` decisions that feed `onInterceptorDecision`.
- **[`docs/streaming.md`](streaming.md)** — `AgentSession` / `AgentEvent` surface the bridge consumes.
- **[`docs/model-and-tools.md`](model-and-tools.md)** — existing observer hooks (`onToolUse`, etc.) that the bridge composes with.
- **[`docs/threat-model.md`](threat-model.md)** — observability is a deployment requirement for several scenarios there.
- **[`docs/production-hardening.md`](production-hardening.md)** — "OTel traces exported" is a hardening-checklist item.
- **OTel GenAI semconv** — [opentelemetry.io/docs/specs/semconv/gen-ai/](https://opentelemetry.io/docs/specs/semconv/gen-ai/)
- **LangSmith API v1/v2 overview** — [docs.langchain.com/langsmith/api-v1-v2-overview](https://docs.langchain.com/langsmith/api-v1-v2-overview)
- **Langfuse ingestion API OpenAPI** — [cloud.langfuse.com/generated/api/openapi.yml](https://cloud.langfuse.com/generated/api/openapi.yml)

## W3C trace propagation across MCP / A2A (#3873, slice 1)

Distributed agent traces connect across process boundaries: outbound MCP and A2A requests carry `traceparent`/`tracestate` from the current span, and the receiving `McpServer`/`A2AServer` makes the remote context current for the dispatch. The seam is no-op (zero overhead, zero OTel dependency in core) until you install the wiring:

```kotlin
OtelTracePropagation.install()       // from :agents-kt-otel; uses GlobalOpenTelemetry propagators
```

Pair with `OtelBridge` for the in-process spans. Remaining on #3873: native session→turn→skill→tool span hierarchy emitted by the runtime itself (today the bridge translates audit events), and coroutine `ContextStorage` integration so user HTTP-client auto-instrumentation inherits the context across suspensions.
