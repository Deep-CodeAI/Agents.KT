---
description: Source-file knowledge for agents_engine/core/Skill.kt — the Skill<IN, OUT> unit-of-work class, deterministic vs agentic flavors (implementedBy vs tools(...)), the freeze contract (#668), knowledge entries surfaced via toLlmContext() and knowledgeTools(), memory opt-in (#856 useMemory()), output transformer for typed OUT, auto-description with kotlin-reflect graceful degradation (#1718). Call when the IDE LLM needs to reason about how skills are declared, what they do, or how they're frozen.
---

# `agents_engine/core/Skill.kt` — the unit of work

`Skill<IN, OUT>` is what an `Agent<IN, OUT>` dispatches to. The agent picks one skill per invocation (by skill-selection rules); the skill produces the output.

## Two flavors

A skill is **either deterministic OR agentic** — never both. The framework chooses the execution path based on which configurator was called last:

| Form | Declaration | Execution |
|---|---|---|
| Deterministic | `implementedBy { input -> ... }` | Pure Kotlin lambda. No LLM round-trip. |
| Agentic (typed, preferred) | `tools(toolA, toolB, ...)` | LLM-driven multi-turn loop pinned to those tools. |
| Agentic (string, soft-deprecated) | `tools("name1", "name2", ...)` | Same, but tool names are strings — kept for built-ins like `escalate`, `throwException`, `memory_*`. |
| Agentic (empty) | `tools()` (no args) | LLM-driven with NO tools (memory + built-ins only). |

Calling `tools(...)` after `implementedBy { ... }` (or vice versa) flips the skill; the last configurator wins. Most callers only use one.

## DSL example

```kotlin
val parse = skill<String, Spec>("parse", "Parse a request into a Spec") {
    implementedBy { input -> Spec(input.split(",").map { it.trim() }) }
}

val solve = skill<Spec, Answer>("solve", "Solve a Spec using the math tools") {
    tools(addNumbers, multiplyNumbers)   // typed — caught at compile time
    transformOutput { rawText -> Answer.parse(rawText) }
    knowledge("conventions") {
        "Always return answers in scientific notation."
    }
    useMemory()                          // opt into memory_* tools
}
```

## Freeze contract

`frozen = true` is set by `Agent.validate()` at the end of construction. After that, every mutator method (`implementedBy`, `tools(...)`, `llmDescription`, `knowledge`, `transformOutput`, `useMemory`) guards with `checkNotFrozen()` and throws `IllegalStateException` on attempted mutation (#668).

The freeze prevents this hazard: a caller keeps a reference to a `Skill` after the agent is built, then mutates it in a way that diverges from the agent's allowlist or agentic/deterministic flag. With the freeze, that scenario fails fast.

## Knowledge entries

```kotlin
skill<String, String>("draft", "...") {
    knowledge("style-guide", "Voice + tone rules") {
        loadResource("style-guide.md")
    }
}
```

Knowledge entries are surfaced two ways:
- **Inlined into the prompt** via `toLlmContext()` — every entry's content gets appended after the skill description.
- **As separately-invocable tools** via `knowledgeTools()` — each entry becomes a `KnowledgeTool(name, description, call)` that the agentic loop exposes alongside regular tools.

Keys are unique per skill; duplicate calls throw at construction.

## Memory opt-in (#856)

```kotlin
skill<X, Y>("...") { useMemory() }
```

When ANY skill on the agent opts in by calling `useMemory()`, the agentic loop respects the opt-in: ONLY opted-in skills receive `memory_read` / `memory_write` / `memory_search` in their allowlist. When NO skill opts in, the legacy "every skill gets memory if a `memoryBank` is set" auto-inject is preserved for backward compatibility.

## Output transformer

```kotlin
skill<X, Answer>("...") {
    tools(...)
    transformOutput { rawText -> Answer.parse(rawText) }
}
```

For agentic skills where `OUT != String`, the LLM's final text needs coercion into the typed output. The transformer runs on the last assistant message in the agentic loop. Deterministic skills don't need it — `implementedBy { }` already returns `OUT`.

## Auto-description (LLM-facing)

`toLlmDescription()` builds the markdown that the parent agent's skill-selection LLM sees. If `llmDescription("...")` was called, that text is used verbatim. Otherwise, the description is synthesized from:

- `name`, `description`
- `inType.simpleName` + `@Generable` annotation walk (constructor params + `@Guide` descriptions)
- `outType.simpleName` + same
- Knowledge entry keys + their descriptions

Reflection is wrapped in `agents_engine.generation.ReflectionFallback.withReflection { }` (#1718) — when the consumer's classpath is missing `kotlin-reflect`, structural detail degrades to empty strings instead of throwing `LinkageError`. The agent still runs; the prompt just lacks per-class structure.

## Builders

- Top-level `skill<IN, OUT>(name, description) { ... }` — creates a standalone `Skill<IN, OUT>` (used by tests, examples).
- `SkillsBuilder` (inside `skills { }` in the agent DSL) — `Skill<IN, OUT>.unaryPlus()` registers a pre-built skill, `skill<IN, OUT>(...)` builds-and-registers in one call.

## Related files

- `Agent.kt` — the dispatcher that picks one skill per invocation.
- `SkillRoute.kt` — manual skill-selection routing.
- `AgenticLoop.kt` — the multi-turn loop that runs for agentic skills.
- `Tool.kt` / `ToolDef.kt` — typed tool handles passed to `tools(...)`.
- `KnowledgeTool` (this file) — the public shape exposed via `knowledgeTools()`.
