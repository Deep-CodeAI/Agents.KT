# Regulated Deployment Guide

For teams shipping Agents.KT-based systems into industries with statutory audit obligations: finance, healthcare, public sector, critical infrastructure, EU-AI-Act-in-scope deployments. This page extends the [production hardening checklist](production-hardening.md) with the additional artifacts and processes regulated buyers expect.

> **Important:** This is engineering guidance, not legal advice. Statutory compliance (HIPAA, PCI DSS, SOC 2, EU AI Act, etc.) requires a qualified compliance professional reviewing your specific deployment. Agents.KT emits **audit-ready evidence that can support** compliance workflows; it does not "make you compliant." If your vendor or counsel says otherwise, get a second opinion.

## What regulated buyers ask for

Regulated buyers don't ask "is this framework secure?" They ask:

1. **Capability inventory:** what is this AI system allowed to do? (Static evidence — answered ahead of time.)
2. **Action log:** what did this AI system do? (Dynamic evidence — answered after the fact, with retention.)
3. **Decision points:** when did a human approve a high-stakes action? (Audit trail with explicit consent records.)
4. **Failure modes:** what happens when the AI is wrong or unavailable? (Documented and tested.)
5. **Data lineage:** what data went into the prompt, what came out, where does it go? (PII handling, retention, jurisdiction.)
6. **Vendor risk:** what third parties (LLM providers, MCP servers) does it depend on? (DPAs, SCCs, subprocessor lists.)

This guide maps each of those questions to Agents.KT primitives and your operational responsibilities.

## 1. Capability inventory

**The artifact:** a static document — checked into the repo and reviewed in CI — that lists every agent, every skill, every tool, every MCP server the agent talks to, and every LLM provider it can invoke.

**Framework support:**
- **Today:** the agent DSL IS the inventory. `agent { skills { skill { tools(...) } } mcp { server() } model { } }` is reviewable Kotlin code. Tag every PR that changes the surface for compliance review.
- **0.6.0:** the permission manifest (#1912) ships this as a generated artifact — a serialized capability graph emitted at build time, hashable, reviewable as a diff in CI. Until it lands, generate by hand: per agent, write down `skills × tools × MCP capabilities` as a markdown table.

**Recommended template** (use until #1912 ships):

```markdown
# Capability Inventory — <agent-name>

| Skill | Input | Output | Tools | Knowledge | Memory | Notes |
|---|---|---|---|---|---|---|
| greet | String | String | greetTool | — | no | low risk |
| approveLoan | LoanRequest | Decision | scoreModel, queryHistory | policy.md | yes | HIGH RISK — see oversight section |

LLM provider: Anthropic claude-opus-4-7 (workspace-scoped key: `loan-app-prod`).
MCP servers consumed: none.
MCP server exposed: yes (port 8443, behind Envoy mTLS).
```

## 2. Action log

**The artifact:** an append-only log of every agent invocation, every tool call, every skill decision, every LLM round-trip. Retention period: per your industry (HIPAA: 6 years; financial: 7 years; GDPR: data-minimum subject to retention exceptions).

**Framework support:**
- **Today:** `Agent.observe { event -> ... }` (sealed `PipelineEvent` view) emits the events. You write them to your retained log. JSONL into a WORM bucket (S3 with Object Lock, GCS Bucket Lock, Azure Immutable Storage) is the typical shape.
- **#1914:** ships a first-party JSONL exporter so the log format is canonical and you don't roll your own JSON shape.
- **#1913:** adds a manifest hash + request/session IDs to every event so the log can prove "this invocation ran against THIS version of the capability inventory."

**Until #1914 / #1913 land**, the rollable pattern:

```kotlin
val auditAppender = JsonlAuditAppender("/var/log/agents-kt/audit.jsonl")
agent.observe { event ->
    auditAppender.append(
        mapOf(
            "timestamp" to event.timestamp.toString(),
            "agentName" to event.agentName,
            "requestId" to MDC.get("requestId"),       // your gateway sets this
            "manifestHash" to MANIFEST_SHA,            // baked at build time
            "event" to event::class.simpleName,
            // ... event-specific fields
        )
    )
}
```

**Evidence-pack contents** (what an auditor will request):
- The action log for the requested time window.
- The capability inventory in effect at that time (matched by `manifestHash`).
- The agent JAR + its dependency manifest (`./gradlew dependencies > deps.txt`).
- The LLM-provider receipts (Anthropic Console, OpenAI dashboard) showing token usage and request IDs for the same window.

## 3. Decision points

**The artifact:** per high-stakes action, a record of: who approved, when, what was approved, what the agent recommended.

**Framework support:**
- **Today:** roll-your-own pattern. The agent returns a typed `PendingAction(plan, requiresApproval = true)`; your service prompts the user; on approval, a second agent invocation executes with `PendingAction(plan, approvedBy = userId, approvedAt = now())` as input. Both rounds appear in the action log.
- **#1907:** ships the `onBefore*` interceptor family — `onBeforeToolCall { ... -> Decision.Confirm(messageToUser) }` — so the approval step is first-class instead of a two-invocation dance.

**Define "high-stakes" up front:**
- Any tool that mutates external state (writes, posts, sends, transfers).
- Any tool that touches PII.
- Any LLM-generated decision that goes to a human (loan, claim, hire, fire, contract).

Low-stakes tools can run without confirmation; the line is YOUR call and should be documented in the capability inventory.

## 4. Failure modes

**The artifact:** documented behavior under each failure mode + test evidence.

| Failure mode | Framework primitive | Your responsibility |
|---|---|---|
| LLM provider 500 / network error | `LlmProviderException` thrown; `onError` listener fires | Graceful UX, fallback provider OR explicit "unavailable" |
| Tool timeout | `BudgetConfig.perToolTimeout` fires `BudgetExceededException(PER_TOOL_TIMEOUT)` | Retry policy, surface to user |
| Tool body throws | `ToolExecutionException` wraps; `onError` fires | Categorize: transient → retry, permanent → escalate |
| Budget cap hit (turns / tokens / duration) | `BudgetExceededException(reason)` | Log the reason; degrade gracefully (return best partial answer with a note) |
| LLM returns malformed structured output | `transformOutput`'s parse fails; `IllegalStateException` | Retry up to N times with `onError.deserializationError { retry(N) }`; escalate after |
| Tool returns malformed args (LLM hallucinated arg shape) | Argument repair loop (up to 8 retries) in `AgenticLoop` | None — automatic |

**Test evidence required:**
- Stub `ModelClient` test for each failure mode showing the agent's user-facing behavior.
- Live-LLM smoke test asserting the happy path against the real provider.

## 5. Data lineage

**Question:** for any given LLM invocation, what data was in the prompt, what came out, where did the output go?

**Framework primitives:**
- `LlmMessage.content: String` is what the provider sees. Your job to know what you put there.
- `onToolUse { name, args, result -> ... }` shows what flowed in and out of each tool.
- `AgentEvent.Token` / `Completed` shows what the model generated.

**Lineage discipline:**
- **Tag every prompt** with the data sources that contributed: `prompt = "User question: ${req.text}\nContext: ${kb.retrieve(req.id)}"` — log `kb.retrieve(req.id)`'s source IDs.
- **Avoid free-form PII in prompts.** Use tokens (`CUSTOMER_42`) and let the agent's tools de-token at the boundary if needed.
- **Record the model + version + temperature in the action log.** "Anthropic claude-opus-4-7-20250514 @ 0.7" is part of the evidence.
- **Region affinity for LLM providers.** Anthropic offers EU-region endpoints; OpenAI offers Azure-hosted with regional control. Pick the region per your data-residency obligation.

## 6. Vendor risk

**Question:** what third parties does this system depend on, and what's the legal posture for each?

**Inventory** (per agent):
- LLM provider (Anthropic, OpenAI, Ollama-local, etc.) — DPA in place? SCCs if EU data? Subprocessor list reviewed?
- MCP servers consumed (`mcp { server() }`) — internal? Third-party? Same DPA questions for each external one.
- LLM-provider subprocessors — Anthropic / OpenAI use AWS / GCP / Azure for hosting; that's a sub-subprocessor in your chain.

**Operational answer:** the framework can't help with the legal side. It CAN help with the technical side: pin provider versions (`claude-opus-4-7-20250514` not `claude-latest`) so a model-behavior change isn't a vendor surprise; pin MCP server versions (in your IaC) so the tool surface doesn't drift under you.

## EU AI Act notes

The AI Act treats different deployment shapes differently. Where your deployment lands depends on use case, not framework choice — Agents.KT itself is a runtime, not an AI system. But the EVIDENCE the Act asks for maps cleanly to the artifacts above:

| AI Act Art. | Asks for | Agents.KT artifact |
|---|---|---|
| Art. 9 (risk management) | Documented risk assessment | Capability inventory + threat model + production hardening checklist |
| Art. 12 (record-keeping) | Automatic logs of operation | Action log via `Agent.observe { }` (#1914 ships canonical exporter) |
| Art. 14 (human oversight) | Human-in-the-loop for high-risk | Decision-points pattern; #1907 makes it first-class |
| Art. 13 (transparency) | User-facing disclosure | Your product's job; not the framework's |
| Art. 15 (accuracy / robustness) | Tested behavior under failure modes | Failure-mode tests + stub `ModelClient` |

**Disclaimer reprised:** the framework emits evidence; classification, conformity assessment, and ongoing compliance are your counsel's call. Cite the framework's role accurately ("the runtime emits audit-ready logs and a capability inventory") not aspirationally ("we use an AI Act-compliant framework").

## Evidence pack template

When a regulator or buyer asks "show me what this AI system does," ship:

1. **Capability inventory** for the agent (or the generated manifest once #1912 lands).
2. **Hardening checklist** marked with the items in effect for this deployment (from [production-hardening.md](production-hardening.md)).
3. **Threat model + scenario classification** — which of the 5 scenarios in [threat-model.md](threat-model.md) this deployment matches.
4. **Action log sample** for the requested time window.
5. **Test evidence** — output of `./gradlew test` and the failure-mode tests.
6. **Vendor list** — LLM providers + MCP servers + their DPAs / subprocessor lists.
7. **Deployment runbook** — how the agent is started, monitored, killed.

Keep this as a templated checklist in your repo so any team member can produce it without 3 hours of archaeology.

## Related docs

- [`docs/production-hardening.md`](production-hardening.md) — the actionable checklist this guide extends.
- [`docs/threat-model.md`](threat-model.md) — scenarios + anti-patterns.
- [`SECURITY.md`](../SECURITY.md) — disclosure + shared responsibility.
- [`docs/whitepapers/`](whitepapers/) — long-form positioning for the regulated-JVM audience (#1921, in flight).
