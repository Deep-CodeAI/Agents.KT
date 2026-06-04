# Production Hardening Checklist

Before going live with an Agents.KT-based system that touches production data, real money, or end users, walk this list. Each item is a checkbox; each item names which Agents.KT primitive enforces it (or "deployer responsibility — framework doesn't help here yet" when the framework doesn't).

This is the **actionable companion** to [`docs/threat-model.md`](threat-model.md). The threat model frames scenarios; this list ships a recipe.

## Shared responsibility

| You handle | Agents.KT handles |
|---|---|
| TLS, gateway rate limiting | Typed `Agent<IN, OUT>` boundaries |
| External ingress identity | `McpServerAuth` bearer-token principals |
| Tool implementation safety (what your lambdas reach) | Tool allowlist per skill |
| Sandboxing tool execution | Budget caps, freeze contract, observability hooks |
| PII redaction in prompts/logs | The hooks to do that redaction (`onToolUse`, etc.) |
| Network policy / egress control | Declarative `ToolPolicy` metadata for review; deployer-enforced network controls |
| Audit log retention + chain-of-custody | Lifecycle events (`AgentEvent`, `PipelineEvent`) with `requestId` / `sessionId` / `manifestHash` |
| Secret rotation | API-key-masked `toString()` on `ModelConfig` |

The framework gives you the primitives. Wiring them to your runtime, infra, and compliance posture is your job.

## Checklist

### Tool surface

- [ ] **Explicit tool allowlist per skill.** Use the typed form: `skill.tools(addTool, multiplyTool)` not `tools("addTool", "multiplyTool")`. Compile-time-checked refs catch typo'd skill names before they ship. *Enforced by:* `Skill.tools(first: Tool<*,*>, vararg rest)`.

- [ ] **No high-privilege tools in model-callable paths.** Tools that touch credentials, the filesystem outside a sandbox, or arbitrary network egress should NOT be in a skill the LLM picks from. Move them behind manual `skillSelection { input -> ... }` or out of the agent entirely. *Enforced by:* you reviewing every `tools(...)` block in code review.

- [ ] **Tool output wrapped or sanitised** before feeding into the next LLM turn. Use `ToolDef(... untrustedOutput = true)` for tools that ingest user-provided content. The flag is currently a signal (no enforcement); use it as a documentation marker AND wrap the lambda's return value yourself: `"--- BEGIN UNTRUSTED CONTENT ---\n$raw\n--- END ---"`. *Partial enforcement:* `untrustedOutput` flag exists; sandbox enforcement ships in Phase 3.

- [ ] **Filesystem / network tools never exposed without a policy.** Declare expected scope with `tool { policy { filesystem { read("/uploads/**"); writeNone() }; network { denyAll() } } }`, then enforce that scope in the tool body or host sandbox. The framework's `tools(...)` allowlist controls WHICH tools the LLM may call; the declarative policy is audit evidence, not 0.6.0 enforcement. *Partial framework support:* `ToolPolicy` (#1915); enforcement remains deployer / #1916.

- [ ] **Dangerous tools run out-of-process.** Until tool sandboxing ships (Phase 3), invoke shell-exec / subprocess / `eval`-style tools through a separate sandboxed process (Docker, gVisor, Firecracker, browser-based WASM). The agent's tool body becomes a thin RPC client to the sandbox. *Deployer responsibility.*

### MCP server (if you expose one)

- [ ] **MCP server bound to loopback (`127.0.0.1`) + fronted by a gateway.** `McpServer` defaults to trusted-local mode and rejects non-loopback callers. For externally reachable service paths, terminate TLS and rate-limit at Envoy/Nginx/Cloudflare Tunnel, then keep `McpServerAuth` enabled in-process. *Enforced by:* `McpServerAuth.TrustedLocal` / `RequireBearerToken`.

- [ ] **Host and Origin allowlists configured.** Browser-reachable deployments should pin both the public host and the expected IDE/web origin. *Enforced by:* `allowedHosts` and `originAllowlist`.

- [ ] **Per-client MCP tool policy.** Use bearer-token principals to filter `tools/list` and deny `tools/call` without leaking whether the tool exists. *Enforced by:* `toolPolicy { principal, toolName -> ... }`.

- [ ] **`expose()` only the skills that should be MCP-callable.** Default to opaque: `expose("safe-read-tool")` not "every skill." Audit the call list in code review. *Enforced by:* `McpServer.from(agent) { expose(...) }`.

### Budgets

- [ ] **Conservative `BudgetConfig` per agent.** User-facing agents: `maxDuration = 30.seconds`, `maxTokens = 8_000`, `maxToolCalls = 16`. Backend / batch agents can be more generous. *Enforced by:* `BudgetConfig` + `AgenticLoop`.

- [ ] **`perToolTimeout` set to a sensible value.** Don't leave it `null` for tools that hit the network. 5s for fast APIs, 30s for slow ones. The cap fires whether or not the tool body has its own timeout. *Enforced by:* `BudgetConfig.perToolTimeout`.

- [ ] **`maxConsecutiveSameTool` set** to catch the "LLM stuck retrying the same broken call" pathology. Default `null` (no cap); set to `3-5` for production. *Enforced by:* `BudgetConfig.maxConsecutiveSameTool` (#969).

- [ ] **`onBudgetThreshold(0.75)` listener wired to your alerting.** Pre-cap warning so you know before the hard throw. *Enforced by:* the listener; you do the wiring.

### Secrets

- [ ] **No raw API keys in source.** `model { claude(...); apiKey = System.getenv("ANTHROPIC_API_KEY") }`. Compile-time check: grep your repo for `apiKey = "sk-"`. *Partially enforced by:* `ModelConfig.toString()` masks the key; doesn't prevent you from hard-coding it.

- [ ] **Provider-side key scoping.** Anthropic supports workspace-scoped keys; OpenAI supports project keys. The key the agent uses should not have org-wide permissions. *Deployer responsibility.*

- [ ] **Secrets redacted from logs.** Use the first-party JSONL exporter for canonical audit rows; it omits raw tool arguments/results by default. If you add custom `onToolUse { name, args, result -> ... }` logging, scrub before writing. *Framework gives you the safe exporter and the raw hook; custom logging is your responsibility.*

- [ ] **PII not in the prompt.** Sanitize user input before it becomes part of the system or user prompt. Anthropic / OpenAI retain prompts; don't ship them PII. *Deployer responsibility.*

- [ ] **Key rotation runbook documented.** When you rotate a provider key, the env var changes; the agent picks it up on next restart. Document the rotation steps for your ops team. *Deployer responsibility.*

### Observability

- [ ] **`onToolUse` wired to your trace system.** Every tool call should produce a span / log line with name, args (redacted), result-size, duration. *Enforced by:* the hook; you do the wiring. OpenTelemetry adapter via #1908.

- [ ] **`onError` wired to your alerting.** Errors that propagate to the agent boundary are usually retry-or-page; route them. *Enforced by:* the hook; you do the wiring.

- [ ] **`onLLMError` policy decided (#3508).** When a model is configured, a failed model call in the agentic loop — a down provider (surfaces as the raw transport error, e.g. `ConnectException`), a 5xx, a malformed response — **fails fast and loud by default**. Register `agent.onLLMError { e -> … }` only if you want graceful degradation: return `LlmErrorDecision.RespondWith(fallback)` to use a canned/typed result instead of throwing, or `LlmErrorDecision.Rethrow` (the default) to keep failing loud. The handler receives the original exception (identity-preserved); it does **not** fire for budget caps (`onBudgetExceeded`) or cancellation. With **no model configured**, the agent runs `implementedBy` skills deterministically and no model error can arise. *Enforced by:* the hook; default is loud. Recovery is scoped to the agentic loop in this release; a model failure during multi-skill LLM routing still propagates.

- [ ] **`Agent.observe { event -> }` for unified telemetry.** Sealed event view across `SkillChosen` / `ToolCalled` / `KnowledgeLoaded` / `ErrorOccurred`. Useful for one-listener-to-rule-them-all dashboards. *Enforced by:* `PipelineEvent` sealed interface (#965).

- [ ] **JSONL audit log emitted.** Use `:agents-kt-observability`:
  `agent.events.export { jsonl(file("/var/log/agents-kt/audit.jsonl"), rotation = JsonlRotation.Daily()) }`.
  Rows are append-only, `jq`-friendly, and carry `requestId`, `sessionId`, and `manifestHash`; raw arguments/results are not serialized. *Enforced by:* `JsonlAuditExporter` (#1914); you handle retention and chain-of-custody.

- [ ] **OTel traces exported.** Use `:agents-kt-otel` and `.observe(OtelBridge(tracer))` to map agent sessions, model turns, tool calls, errors, budgets, and interceptor decisions to OTel spans/events. *Enforced by:* `ObservabilityBridge` + `OtelBridge` (#1908); you configure the SDK/exporter.

- [ ] **LangSmith run trees exported, if LangSmith is your trace backend.** Use `:agents-kt-langsmith` and `.observe(LangSmithBridge(apiKey, project))`; the bridge dispatches asynchronously with oldest-drop backpressure logging so trace outages do not break agent execution. *Enforced by:* `ObservabilityBridge` + `LangSmithBridge` (#1909); you own API key/project configuration.

- [ ] **Langfuse traces exported, if Langfuse is your trace backend.** Use `:agents-kt-langfuse` and `.observe(LangfuseBridge(publicKey, secretKey))`; the bridge dispatches native ingestion batches asynchronously with oldest-drop backpressure logging so Langfuse outages do not break agent execution. *Enforced by:* `ObservabilityBridge` + `LangfuseBridge` (#1910); you own key/base URL configuration.

### Governance

- [ ] **Permission manifest reviewed in CI.** Use `:agents-kt-manifest` to generate `agentManifest` JSON/YAML and run `verifyAgentManifest` against an approved baseline. Every PR that changes the agent / tool / MCP-exposed surface should print the capability-graph diff and require explicit reviewer sign-off. *Enforced by:* `permissionManifest()` and the Gradle plugin (#1912); you own the approval workflow.

- [ ] **Human oversight on high-risk decisions.** Use `onBeforeToolCall` / `onBeforeTurn` to deny, mutate, or substitute high-risk actions before they reach tools or the model. For approvals, have the interceptor deny or substitute a pending-action result until your host app records user approval. *Enforced by:* `Decision` before interceptors.

- [ ] **Shared-responsibility statement reviewed by legal / compliance.** Both you and your end users should know what the agent is and isn't allowed to do. The [README Limitations section](../README.md#known-limitations) is the framework's contribution; your product needs its own statement. *Deployer responsibility.*

### Operational

- [ ] **Failover plan for LLM provider outages.** Anthropic / OpenAI / Ollama / DeepSeek go down. Either gracefully degrade ("the assistant is unavailable") or switch providers (`ModelClient` override + retry). *Deployer responsibility.*

- [ ] **Cost monitoring.** `maxTokens` per invocation isn't enough — track aggregate via the `TokenUsage` returned in `AgentEvent.SkillCompleted` / `Completed`. Alert on cost anomalies. *Framework emits; you aggregate.*

- [ ] **Tests for the agent surface.** Use a stub `ModelClient` to test deterministic agent behavior. Cover failure modes (provider errors, tool timeouts, budget overruns). See [`docs/testing.md`](testing.md). *Framework gives you the seam; you write the tests.*

- [ ] **Live LLM smoke test in CI.** One test that runs the real provider and asserts a smoke-level invariant ("the agent returns SOMETHING"). Caught by the `live-llm` tag — opt-in for CI to avoid cost on every PR. *Framework supports the pattern; you decide cost tolerance.*

## Pre-launch ritual

The day before you ship to production:

1. Re-walk this checklist; check off every item OR write down which residual risk you're accepting and who approved it.
2. Run `./gradlew check` (includes the [internals-agent adjunct validator](internals-agent.md) introduced for v0.6.0).
3. Run `./gradlew test` against the production-shaped config (real Anthropic / Ollama / OpenAI key, not the test stub).
4. Manually run the agent through the happy path AND three failure-mode paths (provider 500, tool timeout, budget overrun) and confirm behavior matches expectation.
5. Tail your gateway / app logs while doing (4); confirm sensitive values are NOT appearing.
6. Confirm rollback procedure: how do you turn the agent off if it misbehaves? (Feature flag, kill switch, route disable.)

## Related docs

- [`docs/threat-model.md`](threat-model.md) — companion: scenarios + anti-patterns.
- [`docs/regulated-deployment.md`](regulated-deployment.md) — extends this with industry-specific notes (finance, healthcare, public sector).
- [`SECURITY.md`](../SECURITY.md) — vulnerability disclosure + shared responsibility.
- [`docs/model-and-tools.md`](model-and-tools.md) — budget caps, tool authorization in depth.
- [`README.md` Limitations](../README.md#known-limitations) — current-state caveats.
