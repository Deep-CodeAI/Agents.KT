# Agents.KT v0.7.21 — Security + de-slop

**Release date:** 2026-06-02

A security fix, two correctness behavior changes, a build-wide maintainability refactor, and new
release/quality guards. Drop-in on the 0.7.x line (internal refactors are behavior-preserving).

```kotlin
implementation("ai.deep-code:agents-kt:0.7.21")
implementation("ai.deep-code:agents-kt-ksp:0.7.21")
```

## 🔒 Fixed — nested agent recursion is now bounded (#3377)
Budgets bounded a *single* agentic loop, but a tool that re-invoked an agent (Swarm `absorb`,
agent-as-tool) spun up a fresh loop with a fresh budget — so a self-re-entering agent (A→A) or a
cycle (A→B→A) recursed one full LLM loop per level until `StackOverflowError` (a DoS / runaway-cost
vector, triggerable e.g. by prompt injection into a tool result). Now `AgentRuntimeContext` carries a
nested-invocation `depth` and `budget { maxAgentDepth }` (default **16**) is enforced at the
invocation chokepoint: exceeding it throws `BudgetExceededException(BudgetReason.AGENT_DEPTH)` **before**
the over-deep loop runs. An unconditional safety stop; budget caps also now bypass the `onError`
tool-recovery ladder so a nested cap can't be swallowed.

## ⚠️ Behavior changes
- **Skill routing fails loud on ambiguity (#3087).** When multiple skills match an output type and
  there's no `skillSelection { }` selector and no `model { }`, invocation now throws
  `SkillRoutingException` naming the candidates instead of silently picking the first by registration
  order. Add a selector or a model to disambiguate.
- **`maxAgentDepth` default (#3377).** Agents that legitimately nest deeper than 16 must raise
  `budget { maxAgentDepth = … }`.

## Added — release & quality guards
- **`checkPublishedVersion`** + a release runbook (#3084): fails unless the project version is
  resolvable on Maven Central — the advertised version can't get ahead of the published artifact.
- **`securityCheck`** aggregate gate + **detekt-baseline ratchet** (`checkDetektBaseline`) + honest
  `TESTING.md` (#3089).
- **`checkOneTypePerFile`** guard (#3199) — enforces one top-level type per file across the tree.

## Changed — maintainability (behavior-preserving)
- **One type per file, whole codebase (#3199).** Every multi-type file split into focused files;
  ~110 files reorganized, zero public-API change.
- **`Agent` God-object decomposition (#3088).** Invocation params bundled into `RunRequest`; skill
  resolution extracted into `SkillResolver`.
- **AgenticLoop decomposition started (#3376).** Tool-result rendering + output coercion extracted
  into `ToolResultRendering` / `OutputCoercion` (now unit-tested); more batches to come.
- **Honest README positioning (#3085 / #3086).** Threat-model-specific security claims; accurate
  implemented-vs-roadmap.

## Compatibility
Drop-in for 0.7.x except the two flagged behavior changes (both correctness/safety). New API is
additive (`budget { maxAgentDepth }`); no `agent { }` DSL surface removed.
