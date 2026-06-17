# Threat Model & Deployment Patterns

This page is the bridge between Agents.KT's security model (what the framework enforces) and your deployment (what it actually runs alongside). Read it before going live with anything that touches money, PII, or production infrastructure.

The goal: in five minutes you should be able to self-classify your deployment, see which Agents.KT guardrails apply, and know which gaps you must close yourself.

> **What the framework guarantees vs what you guarantee.** Agents.KT enforces typed boundaries, skill tool allowlists, budget caps, frozen agent state, Layer-1 filesystem-path argument checks for declared `ToolPolicy` (#2890), Layer-2 OS sandboxing for subprocess-shaped tools via `processTool` (#1916), MCP inbound auth (loopback-only by default) with Host/Origin allowlists, and (where you opt in) untrusted-output wrapping. It does **not** sandbox arbitrary in-JVM Kotlin lambdas, filter prompt injection, or replace your gateway, TLS, and rate limiting. The full status of every boundary is the [what's-enforced-where table](#whats-enforced-where-security-relevant-as-of-0724) below; the scenarios tell you how to close your side.

## Trust boundaries

Five boundaries that matter and what you control at each:

| Boundary | Examples | What you control |
|---|---|---|
| **Network ingress** | HTTP MCP endpoint, REPL stdin | Auth, TLS, origin allowlist, rate limit |
| **LLM provider** | Anthropic, OpenAI, Ollama | API key scope, model selection, prompt content |
| **Tool execution** | `executor: (Map) -> Any?` lambdas | What the lambda does, what it can reach |
| **Tool data flow** | Tool output → next LLM turn | Untrusted-output wrapping, sanitization |
| **Process** | The JVM running the agent | Filesystem ACL, network egress, syscall scope |

## Scenarios

### 1. Safe local assistant (single-user, no network egress)

**You**: a developer using an Agents.KT REPL or `runInternalsAgent`-style local MCP server to consult a model about code, documents, or notes on your own machine.

**Trust shape**: you are the only caller. Tools you wire either don't touch the network OR only touch services you've authenticated to. The LLM runs locally (Ollama) so prompts never leave the box. No multi-tenancy.

**Recommended config:**

```kotlin
val assistant = agent<String, String>("local-assistant") {
    model { ollama("gpt-oss:120b") }   // local only — no API key, no egress

    budget {
        maxTurns = 16                   // generous; you'll cancel if it's going sideways
        maxDuration = 5.minutes
        perToolTimeout = 30.seconds     // bounds any one tool call
    }

    skills {
        skill<String, String>("answer") {
            tools(readFile, grep, listDir)   // local-only tools
            // No web fetch, no shell exec, no anything that touches secrets you can't see
        }
    }
}

LiveRunner.serve(assistant, args) {
    prompt = "you> "
    precheck = OllamaPreflight()::check
}
```

**Guardrails that apply:** `BudgetConfig`, single-placement, freeze contract, typed `tools(...)`, no network egress from the agent process.

**Residual risks:** what your tools can read (filesystem, env vars) is what the agent can read. If your `readFile` tool can read `~/.ssh/id_rsa`, the agent can ask it to. Scope the tool's reach.

**Verdict:** Agents.KT-as-shipped is sufficient. No additional hardening needed beyond keeping tools narrow.

### 2. Internal business tool (trusted intranet, authenticated)

**You**: shipping an agent inside a Spring/Ktor service on a corporate intranet. End users are authenticated employees; their HTTP requests hit your service, which invokes the agent, which calls internal APIs (with their JWT) on their behalf.

**Trust shape**: ingress is your service's auth layer (you already have one). The agent's tools call internal APIs with the user's identity. No public internet exposure of the agent itself.

**Recommended config:**

```kotlin
@Singleton
class AgentService(private val claudeKey: String) {
    private val agent = agent<UserRequest, AssistantReply>("ops-assistant") {
        prompt(loadResource("prompts/ops.md"))
        model {
            claude("claude-opus-4-7-20250514")
            apiKey = claudeKey
        }

        budget {
            maxTurns = 8
            maxToolCalls = 16
            maxDuration = 30.seconds       // user-facing — keep tight
            perToolTimeout = 5.seconds     // hard cap per outbound call
            maxTokens = 8_000              // cost ceiling
        }

        skills {
            skill<UserRequest, AssistantReply>("answer") {
                tools(searchKb, fetchTicket, queryMetrics)
                useMemory()                // per-user scratchpad
                transformOutput { Json.decodeFromString<AssistantReply>(it) }
            }
        }

        onError { e -> log.error("agent failure", e) }
        onBudgetThreshold(0.75) { reason, used -> metrics.gauge("agent.budget.${reason}", used) }
    }

    suspend fun answer(req: UserRequest, principal: Principal): AssistantReply {
        // The agent's tools close over `principal` so per-user authz is enforced
        // OUTSIDE the agent — the agent can't widen its own permissions.
        return agent.invokeSuspend(req)
    }
}
```

**Guardrails that apply:** `BudgetConfig` (especially `maxDuration` + `maxTokens`), tool allowlist via typed `tools(...)`, `onError` for observability, no MCP exposure (agent is a library call from your service).

**Gaps you close yourself:**
- **Auth at ingress** — your service's existing auth/authz; not the framework's job.
- **Per-tool authz** — pass the user's `Principal` into the tool lambda's closure, check it there. The agent's allowlist controls WHICH tools, you control WHAT they do.
- **PII redaction** — sanitize `req` before passing to the agent if it'll feed into the LLM prompt. The framework doesn't auto-redact.
- **Output sanitization** — `transformOutput` parses the JSON, but if your reply renders into HTML, escape it at render time.
- **Cost budget at the org level** — `maxTokens` is per-invocation; add Anthropic/OpenAI org-level limits too.

**Verdict:** Agents.KT-as-shipped fits this scenario well. The intranet trust boundary + your existing service auth do the heavy lifting; the framework provides the typed-agent + budget + observability layer.

### 3. MCP server exposed through gateway (multi-client, authenticated)

**You**: running `McpServer.from(agent)` and want external IDE clients (Claude Desktop, Cursor, partner agents) to consume it. Behind a reverse proxy.

**Trust shape**: untrusted-by-default. Clients are authenticated via the gateway. Tools have different sensitivities per client.

**Recommended deployment:**

```
[client] --TLS--> [Envoy / Nginx / Cloudflare Tunnel]
                       ↓ (mTLS or Bearer JWT, with client identity header)
                  [McpServer at 127.0.0.1:8765 — NEVER bound to 0.0.0.0]
                       ↓
                  [agent with budgets + allowlist]
```

```kotlin
val server = McpServer.from(agent) {
    port = 8765
    expose("safe-read-tools")          // narrow exposure surface
    expose("dangerous-write-tools")
    auth = McpServerAuth.RequireBearerTokens(tokens)
    allowedHosts = setOf("agents.internal.example")
    originAllowlist = setOf("https://ide.internal.example")
    toolPolicy { principal, toolName ->
        principal.id == "admin" || toolName == "safe-read-tools"
    }
}.start()
```

**Gateway responsibilities:**
- Terminate TLS.
- Authenticate the client at the edge when you use mTLS / OIDC, or forward a short-lived bearer token that `McpServerAuth` validates.
- Rate limit per client.
- Audit log per request with client identity.

**Guardrails that apply:** `expose(...)` narrows the skill surface; `McpServerAuth` authenticates inbound HTTP callers; `allowedHosts` / `originAllowlist` reject mismatched browser ingress; `toolPolicy` filters `tools/list` and denies `tools/call` without confirming sensitive tool names; `BudgetConfig` caps each invocation.

**Gaps you close yourself (today):**
- **TLS termination and rate limiting.** Keep those at the gateway.
- **Audit log retention.** The framework emits the rows — `agent.events.exportJsonl(...)` (#1914) writes append-only JSONL with `requestId` / `sessionId` / `manifestHash`, and `agent.events.ledger(file)` adds a tamper-evident Merkle chain. That chain records authorized tool calls **and** cross-cutting misbehaviour in one place (#2905): policy/interceptor denials, hallucinated tool calls, budget breaches, and infra errors (by exception class, never the message) — read them back with `ToolAuditLedger.readMisbehaviour(...)`, each row carrying a derived `severity`. Retention, rotation, and chain-of-custody of those files are yours; a gateway log with client identity remains the complement at the edge.

**Verdict:** Agents.KT-as-shipped is the WRONG shape if your gateway can't take on these responsibilities. With a gateway that can, it works; without one, see anti-patterns below.

### 4. Multi-agent swarm exposed to end users

**You**: a captain agent absorbs sibling agents via `Swarm.discover()`, and the captain is the user-facing surface. End users send free-form prompts.

**Trust shape**: same as Scenario 3 (untrusted ingress) PLUS the captain decides which sibling to dispatch to. If a sibling has dangerous tools, the captain becomes the authorization decision point.

**Recommended config:**

```kotlin
val captain = agent<String, String>("captain") { /* ... */ }
Swarm.discover().forEach { sibling ->
    // Audit BEFORE absorbing — log which siblings the captain will have access to.
    auditLog.info("captain absorbing sibling: ${sibling.name}")
    captain.absorb(sibling)
}
```

**Critical:** every sibling's tools become callable through the captain. The captain's prompt and the LLM together pick which sibling to invoke. If a sibling has `executeShellCommand`, the LLM can ask the captain to dispatch to it.

**Hardening pattern:**
- Pre-categorize siblings into "safe to expose to user-driven captains" vs "internal-only."
- The user-driven captain only absorbs siblings from the safe category.
- Internal-only siblings sit behind a separate captain that's invoked from authenticated internal callers (Scenario 2 shape).

**Verdict:** Agents.KT-as-shipped supports this, but the captain-as-authorization-decision-point is YOUR design — the framework doesn't tag siblings as "safe to expose" or "internal."

### 5. Anti-patterns — do not do this

| Anti-pattern | Why it fails |
|---|---|
| Internet-facing `McpServer` bound to `0.0.0.0` with no gateway | Bearer auth and origin checks help, but you still lose TLS termination, rate limiting, request logging, and network isolation. Bind to loopback and front with a gateway. |
| Agent with `executeShellCommand` / `runJavaCode` / `eval`-style tool, exposed to untrusted callers | The LLM will eventually find a prompt injection that gets it to run something the user shouldn't have access to. Build subprocess tools with `processTool(name, policy) { ... }` — it derives an OS sandbox (Seatbelt / bubblewrap / firejail) from the declared policy and **fails closed** when no backend exists (#2914). A raw in-JVM exec lambda gets neither layer; don't ship one to untrusted callers. |
| One agent instance shared across tenants | The freeze contract prevents mutation, but `memory(MemoryBank())` on the agent gives every tenant access to every other tenant's scratchpad. One agent per tenant, OR scope memory bank per call. |
| Tool that ingests user-provided URLs / files and feeds raw output into the next LLM turn | Classic prompt injection vector. Wrap tool output with `untrustedOutput = true` on the `ToolDef` (a signal flag — the `{"trusted":false}` envelope marks the data, but content filtering stays your job) AND prefix the model's view with `--- BEGIN UNTRUSTED CONTENT ---` markers in your tool body. |
| LLM provider with full API key scope (e.g. an Anthropic key that can also access billing) for a single agent | Scope the key. Anthropic supports workspace-scoped keys; OpenAI supports project keys. The agent should NEVER have a key that can do more than it needs. |
| Logging tool args / outputs to a file that gets shipped to a vendor log aggregator | Tool args / outputs often contain user PII or secrets. Redact at the `onToolUse` listener level before logging. The framework gives you the hook; it doesn't redact for you. |
| Agent that calls itself recursively as a tool (via Swarm or otherwise) without a loop budget | `maxToolCalls` and `maxTurns` bound it, but the cost can spiral before the cap fires. Use `Loop` with explicit `maxIterations` for any self-feedback shape. |

## What's enforced where (security-relevant, as of 0.8.0)

This is the canonical status table — README, `SECURITY.md`, and `production-hardening.md` summarize it; when they disagree, this page wins (and that disagreement is a doc bug worth filing).

| Boundary | Status | Enforced by |
|---|---|---|
| Typed `Agent<IN, OUT>` boundaries | ✓ shipped | Compiler |
| Tool allowlist per skill (`tools(...)`) | ✓ shipped | Runtime — unlisted tools are never advertised or callable |
| Budget caps (`maxTurns` / `maxToolCalls` / `maxDuration` / `perToolTimeout` / `maxTokens` / `maxAgentDepth` / `maxToolArgsBytes`) | ✓ shipped | Runtime (`BudgetConfig` + agentic loop) |
| Freeze-after-construction / single-placement rule | ✓ shipped | Runtime, at construction |
| Filesystem-path tool arguments vs declared `ToolPolicy` globs | ✓ shipped (0.7.0, #2890) | **Layer 1** — in-JVM gate, `..`-traversal normalized, denials audited |
| Subprocess tool confinement (write roots, env, default-deny network) | ✓ shipped (0.7.0, #1916) | **Layer 2** — `ProcessSandbox`: macOS Seatbelt / bubblewrap / firejail; `processTool` (#2914) is the fail-closed path |
| Arbitrary in-JVM Kotlin lambda side effects | ✗ not sandboxed | Yours (OS/container layer); `agents-kt-detekt`'s `ToolBodyForbiddenApis` catches raw `File`/`URL`/`ProcessBuilder`/reflection statically |
| Selective network egress (hostname allowlist proxy) | 0.8 planned (#2893) | — (Layer-2 network is deny/allow-all today) |
| Read confinement in the sandbox | 0.8+ planned | — (reads remain broad) |
| WASM / Docker sandbox backends | 0.8 planned (#2894 / #2895) | — |
| `grants { allow / confirm }` capability grants (agent-level) | ✅ shipped (#4545) | `allow(...)` = freely callable; `confirm(...)` = needs the granting agent's authorization (fail-closed). Build-validated that skills stay within grants. Full `structure { root { delegates {} } }` topology still later |
| MCP server inbound auth | ✓ shipped | `McpServerAuth.TrustedLocal` default (loopback-only) / `RequireBearerToken(s)` |
| A2A / NLWeb server inbound posture | ✓ shipped | `A2AServer` (#3864) and `NlWebServer` (#4542) bind `127.0.0.1` only, optional `Bearer` token, front with a gateway — same stance as `McpServer` |
| MCP server Host / Origin validation | ✓ shipped | `allowedHosts` / `originAllowlist` |
| Per-client MCP tool policy | ✓ shipped | `toolPolicy { principal, tool -> }` — filters `tools/list`, denies `tools/call` opaquely |
| `untrustedOutput` flag on `ToolDef` | ✓ signal + envelope | `{"trusted":false}` wrapping marks data-not-instructions; content filtering is yours |
| Prompt-injection filtering | ✗ | Yours (mitigations: `maxToolArgsBytes`, `untrustedOutput`, output wrapping) |
| PII redaction in tool I/O | ✗ | Yours (`onToolUse` hook) |
| Permission manifest / capability graph + CI verify | ✓ shipped | `agents-kt` CLI `generate` / `inspect` / `verify`; `manifestHash` on runtime events |
| JSONL audit export + tamper-evident ledger | ✓ shipped | `exportJsonl` (#1914) + `ToolAuditLedger` (Merkle-chained, `verify()`); records tool actions + misbehaviour — denials/hallucinations/budget breaches/infra errors (#2905), `readMisbehaviour()` |
| `onBefore*` interceptors (proceed / replace / deny / substitute) | ✓ shipped | Runtime (#1907) |
| Human-in-the-loop approval + resume | ✓ shipped | `humanApproval { }` → `ApprovalRequest` → `resumeWith(HumanDecision)` (#2489), fail-closed timeout default |
| Fail-loud ambiguous skill routing | ✓ shipped (0.7.21, #3087) | `SkillRoutingException` — no silent first-match |
| Model-failure policy | ✓ shipped (0.7.23, #3508) | `onLLMError` — fail-fast-loud default, typed opt-in recovery |

## Related docs

- **[`docs/production-hardening.md`](production-hardening.md)** — actionable checklist for "before going live."
- **[`SECURITY.md`](../SECURITY.md)** — reporting vulnerabilities + shared responsibility.
- **[`docs/model-and-tools.md`](model-and-tools.md)** — agentic loop, tool authorization, budget caps in depth.
- **[`docs/mcp.md`](mcp.md)** — MCP client + server details.
- **[`README.md`](../README.md) Limitations section** — current-state caveats.

## When to file a security issue vs feature request

- **Security issue** (private disclosure via SECURITY.md): the framework violates a boundary it claims to enforce. E.g. tool allowlist gets bypassed, freeze contract gets violated.
- **Feature request** (public Redmine): "I want the framework to enforce X" where X is something it currently doesn't claim to enforce. E.g. prompt injection filtering, sandboxing.

The line: if the README or this page says "the framework enforces this," it's a security issue when it doesn't. Otherwise it's a feature request.
