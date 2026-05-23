# Observability — JSONL audit logs + `ObservabilityBridge`

This page covers two layers:

- **Shipped:** `:agents-kt-observability` JSONL audit exporter (#1914), a zero-vendor-dependency on-disk log format for `PipelineEvent` and `AgentEvent` rows.
- **Design draft:** the proposed `ObservabilityBridge` contract and the first concrete adapter (`agents-kt-otel`) ahead of implementation (#1908). The structured-bridge layer that wires events into OpenTelemetry / LangSmith / Langfuse / Phoenix is still planned.

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
For `ToolCalled` rows, `toolPolicyRisk` mirrors the tool's declarative `ToolPolicy.risk`, and `usedDeclaredCapability` is true when the executed tool declares at least one filesystem/network/environment capability.

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

The framework has the **right shape** for observability — `PipelineEvent` (post-hoc sealed type via `Agent.observe`) plus `AgentEvent<OUT>` (cold `Flow` from `agent.session()`) — and the JSONL exporter now gives those events a canonical on-disk record. The next layer is vendor tracing. Every adopter who wants OpenTelemetry / LangSmith / Langfuse traces today writes the same listener-to-span translation by hand.

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

enum class InterceptorPoint { BeforeSkill, BeforeToolCall, BeforeTurn }

fun <IN, OUT> Agent<IN, OUT>.observe(bridge: ObservabilityBridge): Agent<IN, OUT>
```

The `observe(bridge)` extension wires both event surfaces (and once #1907 lands, the interceptor decisions too) into the bridge with one call. Existing `Agent.observe { event -> ... }` callers keep working — the bridge variant is additive.

## Two-module structure

| Module | Purpose | Dependencies |
|---|---|---|
| `:agents-kt-observability` | The `ObservabilityBridge` interface + `Agent.observe(bridge)` extension | Zero vendor deps |
| `:agents-kt-otel` | OTel adapter (`OtelBridge(tracer)`) | `:agents-kt-observability` + `io.opentelemetry:opentelemetry-api` (compileOnly where possible) |

Future adapter modules (`:agents-kt-langsmith`, `:agents-kt-langfuse`, `:agents-kt-phoenix`) each pull only their own vendor dep and the shared contract.

**Hard constraint:** `./gradlew :agents-kt:dependencies | grep -i opentelemetry` returns nothing. The core module's runtime classpath stays vendor-free.

## OTel mapping

The OTel adapter maps to the **OpenTelemetry GenAI semantic conventions**:

| Source event | OTel artefact |
|---|---|
| `AgentEvent.SkillStarted` | Root span `agent.invoke` (or child if parent context present via `Context.current()`) |
| `AgentEvent.SkillCompleted` | Span end + attrs `gen_ai.usage.input_tokens`, `gen_ai.usage.output_tokens` from cumulative `TokenUsage` |
| Each LLM turn (mid-loop) | Child span `gen_ai.operation.name=chat`, `gen_ai.system=anthropic\|openai\|ollama`, `gen_ai.request.model=...`, `gen_ai.request.temperature=...` |
| `AgentEvent.ToolCallStarted` / `ToolCallFinished` | Child span `gen_ai.operation.name=tool`, attrs `tool.name`, `tool.duration_ms`, truncated `tool.args` (PII-safe limit) |
| `PipelineEvent.ErrorOccurred` | Span status `ERROR` + exception event with original throwable |
| Budget threshold crossing | Span event `agent.budget.threshold` with attrs `reason` (TURNS/TOOL_CALLS/DURATION/TOKENS/CONSECUTIVE_TOOL) and `used_percent` |
| Interceptor `Deny` (#1907) | Span event `interceptor.deny` with `reason` |
| Interceptor `Substitute` (#1907) | Span event `interceptor.substitute` (attr `synthetic=true`) |

Every event already carries `requestId`, `sessionId`, and `manifestHash`; bridge adapters propagate them as `agent.request.id`, `agent.session.id`, and `agent.manifest.hash` attributes when present.

**Semconv version pinned** in the adapter's documentation. When the OTel spec moves, the adapter version bumps; old adapters stay on the older spec until updated.

## Worked example

```kotlin
// In a Spring/Ktor service that already has an OTel SDK + exporter wired
val tracer: Tracer = openTelemetry.getTracer("agents-kt-app")

val agent = agent<UserReq, AssistantReply>("assistant") {
    model { claude("claude-opus-4-7-20250514") }
    skills { /* ... */ }
}.observe(OtelBridge(tracer))      // <-- the wire-up

agent.invoke(req)
// → OTel exporter sees a tree of spans:
//   agent.invoke[assistant]
//     ├── gen_ai.operation.name=chat (turn 1)
//     ├── gen_ai.operation.name=tool tool.name=searchKb
//     ├── gen_ai.operation.name=chat (turn 2)
//     └── gen_ai.operation.name=tool tool.name=fetchTicket
```

Parent-context propagation: if the caller starts a span before `invoke`, the agent's root span is a child of it (via `Context.current()` — standard OTel idiom). Trace IDs propagate cleanly through composed pipelines.

## Verifying the contract

Tests use OTel's `InMemorySpanExporter` for deterministic assertions:

1. **Single skill** — one root span; child spans for each turn and tool call.
2. **Nested tool calls** — span tree depth matches the agentic-loop call tree.
3. **Error path** — failing skill surfaces `span.status = ERROR` + an exception event with the original throwable identity preserved.
4. **Budget threshold event** — crossing 75% on `maxTokens` produces a `agent.budget.threshold` event with `reason=TOKENS` and `used_percent ≈ 75`.
5. **Parent context propagation** — `tracer.spanBuilder("outer").startSpan()` before `invoke` → the agent's root span has the outer span as parent.
6. **Token usage attrs match `Completed.tokenUsage`** — no double-counting across turns; cumulative number matches the final emitted event's value.

## Sibling adapters

Once `:agents-kt-otel` ships, `:agents-kt-langsmith` (#1909) and `:agents-kt-langfuse` (#1910) follow the same shape:

- New module, depends on `:agents-kt-observability` + the vendor SDK.
- Single bridge implementation (`LangSmithBridge(client)`, `LangfuseBridge(client)`).
- Vendor-specific mapping in the bridge body — LangSmith's run-tree shape, Langfuse's session/trace/observation hierarchy.
- Same test pattern with the vendor's in-memory test exporter where available.

The shared contract means a switch from one vendor to another is one line: `.observe(OtelBridge(tracer))` → `.observe(LangSmithBridge(client))`. No re-instrumentation.

## Phoenix and other open-source observability tools

Arize Phoenix, OpenLLMetry, and similar OSS observability stacks already consume OTel GenAI semconv. They get the `:agents-kt-otel` adapter for free — no separate module needed. Document the wiring pattern in this doc; no new module gets cut for them.

## What this does NOT do

- **Logging.** This is for traces / spans. Logs come from your existing logger; `onError` listener is the typical wire-up.
- **Metrics.** Counters / gauges / histograms are a separate concern. The bridge could emit OTel metrics too, but v1 ships traces only. Future expansion.
- **PII redaction.** The bridge passes through what the framework events carry. If args contain PII, redact in a listener BEFORE the bridge sees them — chain the listener: `agent.onToolUse { name, args, result -> redact(...) }.observe(bridge)`.

## Status

| Phase | What it ships |
|---|---|
| Design draft (this doc) | Contract surface frozen, ready for review |
| **Implementation (#1908)** | Two new Gradle modules (`:agents-kt-observability` + `:agents-kt-otel`), bridge contract + OTel adapter + tests with `InMemorySpanExporter` |
| Follow-up adapters | `:agents-kt-langsmith` (#1909), `:agents-kt-langfuse` (#1910) |
| Future | `:agents-kt-phoenix`, metrics emission, OpenLLMetry consumption guide |

Blocking-on: **#1907** (interceptor primitive) so the `onInterceptorDecision` surface is part of the v1 bridge contract and adapters don't need a second integration round when interceptors land.

## Related

- **[`docs/interceptors.md`](interceptors.md)** — `onBefore*` design draft; feeds `onInterceptorDecision`.
- **[`docs/streaming.md`](streaming.md)** — `AgentSession` / `AgentEvent` surface the bridge consumes.
- **[`docs/model-and-tools.md`](model-and-tools.md)** — existing observer hooks (`onToolUse`, etc.) that the bridge composes with.
- **[`docs/threat-model.md`](threat-model.md)** — observability is a deployment requirement for several scenarios there.
- **[`docs/production-hardening.md`](production-hardening.md)** — "OTel traces exported" is a hardening-checklist item.
- **OTel GenAI semconv** — [opentelemetry.io/docs/specs/semconv/gen-ai/](https://opentelemetry.io/docs/specs/semconv/gen-ai/)
