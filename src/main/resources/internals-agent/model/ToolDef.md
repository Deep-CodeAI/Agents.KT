---
description: Source-file knowledge for agents_engine/model/ToolDef.kt — ToolDef (wire shape: Map<String,Any?>→Any? executor + optional session-aware sessionExecutor #1752 + untrustedOutput sandbox flag + argsType KClass for typed coercion), Tool<Args,Result> compile-time-checked handle (#1015/#1016) returned by tool(...) builders. argsType drives constructFromMap deserialization with @Generable. errorHandler slot wired by onError { }. Call when the IDE LLM needs to reason about declaring tools or about typed-vs-stringly-typed tool refs.
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
- `errorHandler` is wired via the typed `tool { ... } onError { ... }` infix.

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

## Argument deserialization

Typed builders register `argsType: KClass<Args>` with `ToolDef`. When the LLM sends args, the loop calls `agents_engine.generation.constructFromMap(argsType, args)` to coerce the `Map<String, Any?>` into a typed `Args` instance — using `@Generable` annotations to drive reflection. Failures raise `ToolError.DeserializationError`, routed through `onError { deserializationError }` if set.

## Related files

- `Tool.kt` (separate file, if present) — extension functions on `Tool<*, *>` for composition.
- `OnErrorBuilder.kt` — the `onError { }` recovery DSL wired to `errorHandler`.
- `ToolError.kt` — typed error union.
- `generation/Generable.kt`, `generation/constructFromMap.kt` — annotation + reflective constructor.
- `AgenticLoop.kt` — calls `executor` (or `sessionExecutor`) per tool invocation.
