---
description: Source-file knowledge for agents_engine/model/ToolDef.kt — ToolDef (wire shape: Map<String,Any?>→Any? executor + optional session-aware sessionExecutor #1752 + untrustedOutput sandbox flag + argsType KClass for typed coercion, plus risk/policy metadata), Tool<Args,Result> compile-time-checked handle (#1015/#1016/#1948) returned by tool(...) builders and implementing core Tool. argsType drives constructFromMap deserialization with @Generable. errorHandler slot wired by onError { }. Call when the IDE LLM needs to reason about declaring tools or about typed-vs-stringly-typed tool refs.
---

# `agents_engine/model/ToolDef.kt` — tool shape + typed handles

The wire-level tool the agentic loop invokes, plus the compile-time-checked `Tool<Args, Result>` handle the `tool(...)` DSL returns.

## ToolDef

```kotlin
class ToolDef(
    val name: String,
    val description: String = "",
    val argsType: KClass<*>? = null,
    val untrustedOutput: Boolean = false,
    val risk: ToolRisk = ToolRisk.LOW,
    val policy: ToolPolicy? = null,
    val sessionExecutor: (suspend (Map<String, Any?>, AgentEventEmitter) -> Any?)? = null,
    val executor: (Map<String, Any?>) -> Any?,
) {
    var errorHandler: ToolErrorHandler? = null
}
```

- `executor: Map<String, Any?> -> Any?` is the wire signature — what the LLM actually sends/reads.
- `argsType` is the `@Generable` Args class for typed tools (`null` for the legacy `tool(name, desc) { args: Map -> ... }` form).
- `sessionExecutor` (#1752): an alternate executor used when the agentic loop is running under a session. Receives an `AgentEventEmitter` so the tool body can stream sub-events (e.g., a sibling agent's inner events) into the captain's session. Falls back to `executor` when null — preserves byte-for-byte behavior for plain tools.
- `sessionExecutor` is declared BEFORE `executor` so the trailing-lambda construction `ToolDef(name, desc) { args -> ... }` still binds to `executor`. (Removing this ordering broke many call sites — see related test failures in the v0.5.0 release.)
- `untrustedOutput`: marks tool outputs as untrusted (sandbox boundary signalling).
- `risk` / `policy`: provider-neutral boundary metadata for the common `core.Tool` contract. Local builder policies set `risk = policy.risk`.
- `errorHandler` is wired via the typed `tool { ... } onError { ... }` infix.

## Declarative policy DSL

The block-style local builder accepts `policy { }`:

```kotlin
tool("readUploadedDocument") {
    description("Read KYC upload")
    policy {
        risk = ToolRisk.Medium
        filesystem { read("/uploads/kyc/**"); writeNone() }
        network { denyAll() }
        environment { allow("OCR_REGION") }
    }
    executor { args -> /* ... */ }
}
```

Typed tool builders also accept an optional `policy = toolPolicy { ... }` argument before the executor lambda.

The policy is declarative only in 0.6.0. It is captured for manifest/audit consumers; sandbox enforcement is not in `ToolDef`.

## Typed handle: `Tool<Args, Result>`

```kotlin
val addTool: Tool<NumberPair, Long> = tool<NumberPair, Long>("addNumbers", "Adds two integers") { args ->
    args.a + args.b
}
```

Returned by every `tool(...)` builder overload. Phantom type parameters let `Skill.tools(...)` accept compile-time-checked refs (#1015 / #1016):

```kotlin
skill<X, Y>("solve") {
    tools(addTool, multiplyTool)        // typo on `addTool` → compile error
}
```

The legacy `tools("addNumbers", "multiplyNumbers")` string form still works for built-ins (`escalate`, `throwException`, `memory_*`) but is soft-deprecated for user tools.

As of #1948, the local handle implements `agents_engine.core.Tool<Args, Result>` so local tools and MCP tools can share later grants/manifests/audit/policy machinery. Its `call(input)` method adapts typed input back to the underlying `ToolDef.executor`.

## Argument deserialization

Typed builders register `argsType: KClass<Args>` with `ToolDef`. When the LLM sends args, the loop calls `agents_engine.generation.constructFromMap(argsType, args)` to coerce the `Map<String, Any?>` into a typed `Args` instance — using `@Generable` annotations to drive reflection. Failures raise `ToolError.DeserializationError`, routed through `onError { deserializationError }` if set.

## Related files

- `Tool.kt` (separate file, if present) — extension functions on `Tool<*, *>` for composition.
- `core/Tool.kt` — provider-neutral tool boundary contract implemented by local and MCP handles.
- `core/ToolPolicy.kt` — declarative policy data classes/builders and manifest helpers.
- `OnErrorBuilder.kt` — the `onError { }` recovery DSL wired to `errorHandler`.
- `ToolError.kt` — typed error union.
- `generation/Generable.kt`, `generation/constructFromMap.kt` — annotation + reflective constructor.
- `AgenticLoop.kt` — calls `executor` (or `sessionExecutor`) per tool invocation.
