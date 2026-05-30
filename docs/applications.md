[← Back to README](../README.md)

# Where Agents.KT Fits Best

The typed agent runtime for the JVM. Where it earns its place — and the runtime features that make each domain work.

---

## The shape of a good fit

Agents.KT is built for one specific kind of agent project: **a JVM team running production workloads where wrong outputs have real consequences, and the agent is a library inside the system — not a service behind an HTTP boundary.**

When all three of those are true, the framework's typed contracts, deterministic permission manifests, compile-time tool-body enforcement, runtime policy gating, and tamper-evident audit ledger stop being "nice to have" and start being load-bearing. The agent doesn't just produce answers — it produces *evidence of what it could and couldn't do*, in a form your compliance team can hand to an auditor.

Five domains where this lands.

---

## 1. Regulated enterprise back-office

Banking, insurance, healthcare, legal, public sector. Workflows where every agent action has a paper trail and the question "what was the model allowed to do?" has a real answer.

| Use case | Load-bearing features |
|---|---|
| **KYC / AML document review** — triage identity docs, flag anomalies, draft reviewer summaries with byte-level references back to the source. | Permission manifest (deterministic capability graph + `manifestHash` correlation), JSONL audit export, **tamper-evident `ToolAuditLedger`** (Merkle-chained; `agent.events.ledger(file)` → `verify()` detects edit/insert/delete/reorder), `@Generable` typed outputs, vision input on scanned IDs. |
| **Insurance claims processing** — multi-step intake → fraud scoring → adjuster notes, with escalation gates for edge cases. | Snapshot/resume so a multi-day claim can pause and continue, **HITL interrupt** primitive for human approval gates, `onBeforeToolCall` policy hooks, audit lineage anchored to the manifest hash, `manifestHash` restore guard refuses to resume against a different tool set. |
| **Clinical document triage** — prior-auth drafting, radiology-report formatting, chart-review summarisation. | Local-first via Ollama (PHI never leaves the building), `untrustedOutput` containment on patient-record tools, **`ToolBodyForbiddenApis` detekt rule** bans raw `File` / `URL` / `ProcessBuilder` / reflection inside tool executors at compile time, declarative `ToolPolicy` recorded in the manifest review process. |
| **Legal contract review** — clause classifier, redlining, discovery triage. | `Branch` operator for "is this an NDA / SaaS / employment contract", `ContentRef` + `BlobStore` (auditable references — never raw bytes in logs), vision attachments on scanned PDFs, snapshot persistence of image refs (#2866). |
| **Public-sector citizen services** — FOIA-request triage, regulatory-filing pre-check, citizen-intake agents. | EU AI Act mapping in [`regulated-deployment.md`](regulated-deployment.md), full audit lineage, deterministic permission manifests, sovereign deployment via Ollama, `ProcessSandbox` (Linux bwrap/firejail) for the highest-trust tool isolation when needed. |

**The positioning:** Python frameworks bolt audit on after the fact. Agents.KT builds it into the type system from line one — and turns "policy declared" into "policy enforced at three layers" (compile-time bans, runtime ToolPolicy gate, audit ledger of every state change). The "typed agent runtime for the JVM" claim is the differentiator; the audit story is the proof.

---

## 2. Internal developer tooling

Coding agents, CI/CD agents, DevSecOps. JVM-native, lives inside the build / repo / pipeline.

| Use case | Load-bearing features |
|---|---|
| **IDE coding agents** — code review, in-editor refactoring, doc-generation assistants. | MCP-as-skills (`McpClient.toolSkills()`) fronts many tool servers from one agent, `Tool<Args, Result>` typed handles compile-checked at agent construction, streaming via `agent.session(input)` with `AgentEvent.Token` / `ToolCall*` chunks. |
| **CI/CD agents** — flaky-test diagnosis, dependency-bump validation, release-notes drafting. | `BudgetConfig` caps for runaway loops (`maxTurns` / `maxToolCalls` / `maxDuration` / `maxTokens` / `maxAgentDepth` / **`maxToolArgsBytes`** that rejects oversized injected args before the executor runs), `onBudgetExceeded` extension hook for the recoverable reasons, `Pipeline` composition for read → build → test → summarise chains, reproducible CI eval via `DeterministicModelClient`. |
| **DevSecOps** — vulnerability triage, secret-leak drafting, IaC-policy review. | Declarative `ToolPolicy` records (capability evidence for compliance review), **`ToolCapabilityExtractor`** statically classifies what each executor body does (fs / net / process / env reads), `Forum` operator for multi-tool consensus, manifest-hash correlation in audit. |
| **Code-review bots** — typed structured outputs that flow into existing JVM tooling. | `@Generable` outputs deserialise into your domain types directly — no JSON-to-class glue layer; constrained decoding wired across all seven providers. |
| **Build pipelines** — automated dependency-bump assessment, supply-chain summarisation. | **`onLLMError`** recovery hook: when the model is reachable but flakey, fall back to a typed canned response (`RespondWith(fallback)`) rather than failing the CI build; with no model, `implementedBy` skills run deterministically and no model error can arise. |

**The positioning:** developer tooling lives where the JVM is already installed. The framework's correctness story (typed tools, single-placement rule, ambiguous-skill loud-fail, audit log) maps directly onto "we don't want a wrong commit / wrong merge / wrong release".

---

## 3. Document-heavy and multimodal operations

QC inspection, form processing, support-ticket triage. Vision input flows through the same typed surface as text.

| Use case | Load-bearing features |
|---|---|
| **Quality-control inspection** — image-based defect classifier feeding a manufacturing MES. | Vision input across Ollama / Claude / OpenAI, `ToolResult` multi-part returns, blob-store-backed image refs in audit logs (hash + size + mime, never inline bytes), file-backed snapshot preserves image refs across process restart (#2866). |
| **Receipt / form processing** — OCR-then-structure pipelines reading scanned invoices, extracting line items, validating against PO database. | `@Generable` typed outputs, `Pipeline` for ocr → classify → validate stages, `Branch` for known-supplier routing, `Files.load(path, store, maxBytes)` with default 20 MiB cap to fail-fast on oversized uploads before they hit the JVM heap. |
| **Support-ticket image triage** — auto-classify customer screenshots with confidence scoring. | `agent.invokeWithAttachments(input, attachments)`, audit row records ref hashes (not the screenshots themselves), `untrustedOutput` containment when image-extracted text feeds downstream tools. |
| **Web-grounded research with citations** — agent that fetches live facts from the web with source attribution. | `perplexitySearch` tool (0.7.24) — typed search with `searchMode` / domain allow-deny / context size / recency filters, results render with parsed `search_results[]` citations into the JSONL audit row, `untrustedOutput = true` flags fetched content as data not instructions. |

**The positioning:** vision-capable, audit-clean. "AI describes the image" becomes "AI produces a typed `InspectionResult` that flows into your existing JVM domain model — with the image hash, size, and mime on the audit row".

See the [provider capability matrix](providers.md) for per-provider × content-type support.

---

## 4. Long-running orchestration with audit anchors

Multi-day research, runbook automation, policy-bounded customer chat. Where state survival across process restarts and approval gates matter.

| Use case | Load-bearing features |
|---|---|
| **Multi-day research / due-diligence agents** — investment research, M&A document review across hundreds of PDFs. | Snapshot/resume (process restart never loses progress), `manifestHash` restore guard (refuses to resume against a different tool set), `cachedInputTokens` + `cacheWriteTokens` accounting to keep multi-hour runs affordable, every five `TokenUsage` fields preserved through accumulation (#2867). |
| **Runbook / on-call agents** — automated incident triage with human approval gates. | HITL interrupt primitive, `onBeforeToolCall` policy gates, JSONL audit + Merkle-chained `ToolAuditLedger` for incident retrospectives, snapshot-anchored pause/resume, `maxAgentDepth` bounded recursion. |
| **Customer-facing chat with policy boundaries** — tier-1 support bot that can read accounts and file tickets, but cannot issue refunds. | Per-skill `tools(...)` allowlist as the authorization boundary (not just prompting), `ToolPolicy` declarations recording intent, `PipelineEvent.ToolHallucinated` events when the model tries to call something outside the allowlist, ambiguous-skill resolution now fails loud (#3087) so silent misrouting can't slip through. |
| **Resilient agent pipelines** — long-running flows where transient model failures shouldn't tear the whole job down. | `onLLMError { e -> RespondWith(fallback) | Rethrow }` — typed recovery from down providers / 5xx / malformed responses, original exception identity preserved for `onError` observers. |

**The positioning:** most agent frameworks treat agents as ephemeral. Agents.KT treats *messages-as-state* as the design hinge — snapshot/resume isn't bolted on, it's how the loop is built. Long-running orchestration is a first-class lane, and 0.7's audit ledger means the post-mortem on a 3-day run is a single grep.

---

## 5. Embedded agents inside existing JVM platforms

Spring Boot retrofit, Kotlin Android backends, big-data pipelines. Where the agent runs as a library *next to* business logic — no Python sidecar, no network hop.

| Use case | Load-bearing features |
|---|---|
| **Spring Boot / Micronaut services** adding agentic features to existing CRUD endpoints (classification, summarisation, smart routing). | JVM library (no external service hop), `invoke` (blocking) / `invokeSuspend` (coroutine) for sync/async paths, observability hooks plug straight into existing OTel pipelines via the `agents-kt-otel` bridge, structured concurrency: cancelling the request scope cancels the agent without orphaned coroutines. |
| **Kotlin Android backends** — server-side companion agents (smart compose, search ranking, in-app help). | Same JVM substrate as the rest of the backend, deterministic tests via `DeterministicModelClient`, structured streaming for mobile-friendly partial responses, manifest-hash correlation lets a per-version capability check run at deploy time. |
| **Big-data pipelines** — Spark / Flink jobs that need an LLM hop per record (PII redaction, classification, entity linking). | JVM-native (no Python interop overhead), `chatStream` for per-record streaming, **prompt caching** to amortise the same system prompt across millions of records (Anthropic explicit `cache_control`, OpenAI/DeepSeek/Perplexity automatic prefix caching, Ollama engine-level KV reuse). |
| **Existing observability stacks** — agent invocations that need to land in the same Datadog / Honeycomb / Grafana pipeline as the rest of the service. | `agents-kt-otel`, `agents-kt-langsmith`, `agents-kt-langfuse` bridges through `ObservabilityBridge`; manifest hash + sessionId + tool callId are first-class so the agent's actions correlate to your existing traces. |

**The positioning:** the framework being a JVM library means same heap, same coroutine scope, same observability stack as the rest of your platform. The Python alternative is a network hop you don't need.

---

## What 0.7 made literally true

Earlier versions of this doc described some features as "declared / coming soon". 0.7's progress closed several of those:

- **Compile-time tool-body enforcement** ships via the `agents-kt-detekt` module — `ToolBodyForbiddenApis` (no raw `File` / `URL` / `ProcessBuilder` / reflection in executors) and `ToolCapabilityExtractor` (static fs / net / proc / env capability classification).
- **Tamper-evident audit ledger** ships: `agent.events.ledger(file).verify()` detects edit / insert / delete / reorder of any tool-call event.
- **Runtime policy enforcement** ships at Layer 1: `ToolPolicyEnforcer` (Linux) gates the filesystem and process surfaces against the declared `ToolPolicy`.
- **Argument-size attack mitigation** ships via `BudgetConfig.maxToolArgsBytes` — oversized injected args fail fast before the executor runs.
- **Recursion-bound + ambiguous-routing loud-fail** ship: `maxAgentDepth` (default 16) closes the unbounded self-re-entry vector; ambiguous skill resolution now throws instead of picking-first.
- **Typed model-error policy** ships via `onLLMError`: failed model calls fail loud by default with the original exception, or get typed recovery via `RespondWith(fallback)`.

The capability-ABI epic (#2882) is ~42% done — the surface that's still pending is the `ToolEnvironment` ABI + executor migration, planned for a deliberate breaking-ish slice.

---

## When other tools are the better answer

Credibility comes from saying this honestly.

- **Pure-Python or pure-TypeScript teams.** If the backend is FastAPI + Pydantic + LangChain, dropping a JVM service for the agent is friction without payoff. We have nothing to add — LangChain / CrewAI / AutoGen are the mature options. Come back to Agents.KT if part of your stack ever moves to JVM.
- **Rapid agent experimentation.** If you're exploring "what would 50 agents arguing produce?" or rapidly iterating on prompt designs, Python frameworks have richer experimental APIs. Agents.KT's compile-time discipline pays off once the design has settled and the destination is production.
- **Edge / mobile / embedded.** JVM startup time and heap floor rule out tight-resource environments. A GraalVM native-image path is on the roadmap; until it ships, this is a wrong fit.
- **Untyped prototype with no audit boundary.** If nobody will ever read the audit log AND the output is rendered straight to a user without further processing AND there's no compliance review, the runtime boundary costs more than it earns. Pick something lighter.

---

## How to know it's the right fit

Three questions in order:

1. **Is this a JVM team?** (Already-running Java / Kotlin / Scala backend.)
2. **Are wrong outputs costly?** (Money, time, legal exposure — someone will eventually ask "what did the agent do and why?".)
3. **Should the agent run as a library, not a sidecar service?** (Same heap, same observability, same coroutine scope as the rest of the platform.)

Yes to all three → Agents.KT is in the running. Map the use case to one of the five categories above; the load-bearing-feature columns name exactly which framework primitives carry the value, and which are shipped today.

---

## Related

- [Comparison vs LangChain / Semantic Kernel / AutoGen / raw MCP](comparison.md) — when and why a JVM-native runtime wins.
- [Regulated deployment guide](regulated-deployment.md) — capability inventory, action log, EU AI Act mapping, evidence pack template.
- [Provider capability matrix](providers.md) — per-provider × content-type × caching × tool-choice × streaming.
- [Threat model](threat-model.md) — five deployment scenarios + anti-patterns; self-classify in 5 minutes.
- [Production hardening](production-hardening.md) — checklist for "before going live".
