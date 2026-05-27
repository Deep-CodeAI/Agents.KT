---
title: "Bounded Agent Systems: Audit-Ready Kotlin AI Workflows for Regulated JVM Teams"
status: draft
audience: engineering and platform leads at regulated JVM organisations
version: 0.6.0
date: 2026-05-27
disclaimer: >
  This document is engineering guidance, not legal advice. It describes how
  Agents.KT produces audit-ready evidence that *can support* AI-governance and
  AI Act-aligned workflows. It does not assert that using Agents.KT makes any
  system, organisation, or deployment "compliant" with the EU AI Act or any
  other regulation. Determining regulatory applicability and obligations is the
  responsibility of the deploying organisation and its legal counsel. Nothing
  here should be read as a guarantee of security, conformity, or fitness for a
  regulated purpose.
---

# Bounded Agent Systems
### Audit-Ready Kotlin AI Workflows for Regulated JVM Teams

> **Engineering guidance, not legal advice.** See the disclaimer in the front
> matter. Throughout, we say Agents.KT produces evidence that *can support*
> audit and oversight workflows — never that it makes a deployment compliant.

---

## 1. Executive summary

Agentic AI systems take actions: they call tools, read and write data, reach
external services, and chain model calls into multi-step workflows. For teams
in regulated industries on the JVM, the governance problem is not "is the model
accurate" but "can we show — before deployment and during operation — exactly
what this system is allowed to do, what it actually did, and who could stop it."

Agents.KT is a typed, boundary-first agent runtime for Kotlin. Its design goal
is that the boundaries of an agent system are **declared in code, materialised
as a reviewable artifact, and correlated with what happened at runtime.** Three
properties make this concrete:

1. **Typed contracts.** Every agent is `Agent<IN, OUT>`; every typed tool has a
   `@Generable` argument type. The capability surface is statically known.
2. **A permission manifest.** `agent.permissionManifest()` emits a deterministic
   YAML/JSON capability graph — every agent, skill, tool, declared filesystem /
   network / environment policy, MCP endpoint, provider, and budget — with a
   stable SHA-256. A Gradle task can fail the build when that surface widens.
3. **Correlated runtime evidence.** Every runtime event carries the manifest
   hash plus request and session IDs, and an append-only JSONL audit log records
   tool calls, denials, and errors. Static manifest and dynamic log tie back to
   the same approved capability set.

This paper explains the design and shows the artifacts. It is written for
engineers who must hand an auditor or a risk team something better than "trust
the prompt."

## 2. Why agentic AI creates new audit problems

Traditional software audit assumes the code path is fixed: reviewers read the
code, and the deployed system does what the code says. Agentic systems break
that assumption in three ways:

- **Non-determinism of control flow.** The model decides which tool to call and
  with what arguments. The *set* of possible actions is bounded by the code, but
  the *actual* sequence is decided at runtime by a probabilistic component.
- **Opaque capability surface.** "What can this agent touch?" is usually buried
  across tool registrations, prompt text, and integration glue. There is rarely
  a single place that answers the question.
- **Action without a paper trail.** When an agent reads a file, calls an API, or
  is *blocked* from doing so, that fact is frequently invisible unless someone
  instrumented it ahead of time.

The consequence: reviewers cannot easily establish the two facts governance
frameworks care most about — the *intended* boundary (what is permitted) and the
*observed* behaviour (what occurred), tied together so the second can be checked
against the first.

## 3. Boundary-first agent design

Agents.KT inverts the usual order. Instead of "wire up an agent, then add
guardrails," boundaries are first-class construction-time concepts:

- An agent is **frozen** after construction. Skills, tools, memory, model, and
  budget cannot be mutated post-build; attempts fail fast. The capability set a
  reviewer sees is the capability set that runs.
- Tools are explicitly granted to skills. A skill exposes only the tools it
  lists; there is no ambient tool access.
- Composition (`then`, `branch`, `loop`, `parallel`, `forum`, `wrap`) is also
  typed and inspectable, so a multi-agent system has a single derivable graph.

The boundary is therefore not a runtime hope; it is a structural property of the
constructed object, which is what makes a deterministic manifest possible.

## 4. Typed contracts: `Agent<IN, OUT>`

Every agent declares its input and output types:

```kotlin
val reviewer = agent<RawRequest, ApprovalDecision>("kyc-reviewer") {
    model { openai("gpt-4o-mini"); apiKey = System.getenv("OPENAI_API_KEY") }
    skills {
        skill<RawRequest, ApprovalDecision>("review", "Assess a KYC request") {
            tools(lookupCustomer)
        }
    }
}
```

`IN` and `OUT` are Kotlin types; structured types are `@Generable` data classes
that the framework can serialise and schema-generate. The benefit for audit is
that the **contract is legible**: a reviewer reading `Agent<RawRequest,
ApprovalDecision>` knows the shape of what goes in and what comes out without
running it, and a type mismatch in a composed pipeline is a compile error rather
than a runtime surprise.

## 5. Least-privilege tool access

Tools declare what they are allowed to touch with a `ToolPolicy`:

```kotlin
tool("readCustomerFile") {
    policy {
        risk = ToolRisk.MEDIUM
        filesystem { read("/var/kyc/uploads/**"); writeNone() }
        network { denyAll() }
        environment { denyAll() }
    }
    executor { args -> /* ... */ }
}
```

The policy records the *intended* capability of the tool: which path globs it
reads or writes, which network hosts it may reach, which environment variables
it consults, and a coarse risk level. Two points of honesty matter here:

- In 0.6.0 the policy is **declarative**: it is audit and manifest evidence, and
  it is read by human-oversight hooks (Section 9). It is **not**, by itself, an
  operating-system sandbox. Process/container enforcement is a 0.7.0 work item.
- A declared policy buys *visibility and a place to enforce*, not automatic
  safety. The enforcement section is explicit about this so reviewers calibrate
  their expectations correctly.

## 6. MCP capability exposure and risk

Agents.KT can expose agents over the Model Context Protocol (MCP) and consume
external MCP tools. Because MCP is an ingress point, it is treated as a boundary:

- An exposed `McpServer` supports authentication, origin validation, and
  per-client policy, with a default-deny posture.
- Imported MCP tools carry their upstream input schema into the manifest, so an
  external tool's capability surface is represented in the same artifact as
  internal tools.

The risk to manage is that MCP can widen a system's effective capability set
without a code change in your own repository. Representing MCP endpoints in the
manifest (Section 7) means that widening is visible to manifest review and to
the build-time verification gate.

## 7. Permission manifest as static evidence

The manifest is the hero artifact. `agent.permissionManifest()` produces a
deterministic, ordered capability graph and a SHA-256 over its canonical
content. Provider secrets are masked. The same input always produces the same
bytes, so the manifest is diffable and reviewable in a pull request.

A `verifyAgentManifest` Gradle task diffs the current manifest against a
checked-in baseline and **fails the build on capability widening** — a new tool,
a new MCP endpoint, a broadened policy. Reviewers see surface-area changes before
they merge, rather than discovering them in production.

See Appendix 12.1 for a sample manifest.

## 8. Runtime audit events as dynamic evidence

Static evidence answers "what is permitted." Dynamic evidence answers "what
happened." Every runtime event carries an `AgentRuntimeContext` with
`requestId`, `sessionId`, and `manifestHash`, and the observability surface emits
a typed `PipelineEvent` stream:

- `ToolCalled` — a tool executed, with its declared risk and whether it used a
  declared capability.
- `ToolDenied` — a tool call was **blocked** by a human-oversight hook (Section
  9). This event exists specifically so that *blocked* attempts are recorded;
  an audit log that only captured executed calls would silently omit the most
  governance-relevant events.
- `SkillChosen`, `KnowledgeLoaded`, `ErrorOccurred`, `BudgetThreshold`.

The `JsonlAuditExporter` writes one canonical JSON line per event with stable
field ordering. Raw arguments and results are omitted by default (PII-safe), and
opt-in when an audit consumer needs them. Because every line carries the
`manifestHash`, a reviewer can join a runtime log back to the exact reviewed
capability graph that was authoritative at invocation time. See Appendix 12.2.

## 9. Human oversight hooks

Boundaries you can audit are more useful when a human (or an automated policy)
can intervene. Agents.KT provides before-interceptors that return a typed
`Decision`:

```kotlin
agent.onBeforeToolCall { name, args ->
    val globs = toolMap[name]?.policy?.filesystem?.write?.globs.orEmpty()
    val path  = args["path"]?.toString()
    if (path != null && globs.isNotEmpty() && globs.none { matchesGlob(it, path) })
        Decision.Deny("path '$path' outside declared write policy")
    else Decision.Proceed
}
```

The verdict *is* the action: `Proceed`, `ProceedWith(sanitisedArgs)`,
`Deny(reason)`, or `Substitute(result)`. A `Deny` blocks the executor and
returns the reason to the model as a tool error; the loop continues. Crucially,
a denied call is observable: it fires `onToolDenied` and surfaces as
`PipelineEvent.ToolDenied`, so the oversight decision lands in the same audit
log as executed calls. This is the bridge between the declarative policy of
Section 5 and an enforced, recorded decision.

## 10. Deployment model for regulated JVM teams

A pragmatic adoption path:

1. **Author with boundaries.** Declare typed agents, grant tools per skill,
   attach `ToolPolicy` to filesystem/network/environment-touching tools.
2. **Generate and baseline the manifest.** Commit the manifest; enable
   `verifyAgentManifest` in CI so capability widening blocks merges.
3. **Wire human-oversight hooks.** Enforce the declared policies with
   `onBeforeToolCall` and record verdicts via `onToolDenied` /
   `onInterceptorDecision`.
4. **Export audit evidence.** Enable the JSONL exporter (and/or the OpenTelemetry
   bridge) and ship logs to your existing evidence store. Confirm every line
   carries the `manifestHash`.
5. **Correlate.** For any incident, take the run's `requestId`/`sessionId`, pull
   the audit lines, read off the `manifestHash`, and check it against the
   reviewed manifest baseline.

## 11. Limitations and shared responsibility

Stated plainly, because honest limits are part of audit-readiness:

- **Declarative ≠ enforced (0.6.0).** A `ToolPolicy` is evidence and an
  oversight input, not an OS sandbox. Without an enforcing interceptor, a tool
  can act outside its declared policy. Process/container enforcement is planned
  for 0.7.0; until then, enforcement is your `onBeforeToolCall` hook.
- **Model behaviour is still probabilistic.** Boundaries constrain the *set* of
  possible actions; they do not make the model's choices deterministic.
- **Not legal advice; not a compliance guarantee.** Agents.KT produces evidence
  that can support oversight and AI Act-aligned workflows. Whether a given
  system is in scope of a regulation, and what obligations apply, is for the
  deploying organisation and its counsel to determine.
- **Shared responsibility.** At-rest encryption of audit logs and snapshots,
  secret management, network egress control at the infrastructure layer, and
  retention policies are the deployer's responsibility. The framework provides
  the shape; the deployment provides the controls.

## 12. Appendix

### 12.1 Sample permission manifest (excerpt, JSON)

> Generated by `agent.permissionManifest()`. Secrets are masked; ordering is
> stable; `manifestSha256` is computed over the canonical content.

```json
{
  "agentsKtManifestVersion": 1,
  "format": "agents-kt.permission-manifest",
  "manifestSha256": "3f1c…(64 hex)",
  "subject": { "type": "agent", "agents": ["kyc-reviewer"] },
  "agents": [
    {
      "name": "kyc-reviewer",
      "provider": { "provider": "openai", "model": "gpt-4o-mini",
                    "apiKey": "masked", "apiKeyPresent": true },
      "skills": [ { "name": "review", "tools": ["readCustomerFile"] } ],
      "tools": [
        {
          "name": "readCustomerFile",
          "risk": "medium",
          "policy": {
            "filesystem": { "read": { "globs": ["/var/kyc/uploads/**"],
                                       "mode": "globs" },
                            "write": { "mode": "none", "globs": [] } },
            "network": { "mode": "denyAll", "hosts": [] },
            "environment": { "mode": "denyAll", "variables": [] }
          }
        }
      ]
    }
  ]
}
```

### 12.2 Sample runtime audit log (JSONL)

> One line per event from `JsonlAuditExporter`. Note `manifestHash` on every
> line and a recorded denial (`ToolDenied`) for a blocked, out-of-policy call.

```jsonl
{"event":"ToolCalled","agent":"kyc-reviewer","tool":"readCustomerFile","risk":"medium","usedDeclaredCapability":true,"requestId":"req-7f3a","sessionId":"user-42","manifestHash":"3f1c…"}
{"event":"ToolDenied","agent":"kyc-reviewer","tool":"readCustomerFile","reason":"path '/etc/passwd' outside declared write policy","requestId":"req-7f3a","sessionId":"user-42","manifestHash":"3f1c…"}
{"event":"ErrorOccurred","agent":"kyc-reviewer","error":"BudgetExceededException: maxToolCalls","requestId":"req-7f3a","sessionId":"user-42","manifestHash":"3f1c…"}
```

---

*Draft for internal and external technical review. Engineering guidance, not
legal advice. Sample artifacts mirror the formats emitted by the permission
manifest generator and the JSONL audit exporter; field selection in the audit
appendix is illustrative.*
