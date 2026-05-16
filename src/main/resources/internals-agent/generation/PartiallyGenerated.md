---
description: Source-file knowledge for agents_engine/generation/PartiallyGenerated.kt — immutable accumulator for fields arriving incrementally from an LLM stream. withField folds in deltas (returns new instance), toComplete delegates to constructFromMap and returns T? or null when required fields missing. Typed property access is a planned KSP Phase 2 affordance. Call when the IDE LLM needs to reason about streaming structured-output consumption.
---

# `agents_engine/generation/PartiallyGenerated.kt` — incremental field accumulator

An immutable accumulator for fields arriving one-at-a-time. Designed for streaming structured-output scenarios where an LLM produces `{"a": 1, "b": "hello", "c": [...]}` token by token, and the consumer wants to react as each field completes.

## API

```kotlin
class PartiallyGenerated<T : Any> {
    operator fun get(fieldName: String): Any?              // null if not arrived
    fun has(fieldName: String): Boolean
    val arrivedFieldNames: Set<String>
    fun toComplete(): T?                                   // attempt full construction
    fun withField(name: String, value: Any?): PartiallyGenerated<T>  // immutable fold

    companion object {
        inline fun <reified T : Any> empty(): PartiallyGenerated<T>
    }
}

inline fun <reified T : Any> partiallyGenerated(): PartiallyGenerated<T>
```

## Usage

```kotlin
var partial = partiallyGenerated<Order>()
streamingJsonFlow.collect { (fieldName, value) ->
    partial = partial.withField(fieldName, value)
    if (partial.has("id") && partial.has("total")) {
        ui.show("Order ${partial["id"]} totalling ${partial["total"]}")
    }
}
val complete: Order? = partial.toComplete()
```

## Immutable accumulation

Each `withField(name, value)` returns a NEW `PartiallyGenerated` — the underlying `arrivedFields: Map<String, Any?>` is replaced via `+` (treating the existing map as immutable). This makes the accumulator safe to publish to multiple observers without aliasing concerns.

## `toComplete`

Delegates to `klass.constructFromMap(arrivedFields)`. Returns `null` if:
- Required (non-default-valued) constructor params haven't arrived yet.
- Type coercion fails on any arrived field.
- The constructor itself throws.

Use `toComplete()` to "try every tick" if you want to surface the result as soon as it becomes constructible.

## Typed property access (Phase 2)

Today: `partial["fieldName"] as Type` — untyped at the call site.

Planned (Phase 2 of KSP codegen): `partial.fieldName: Type?` — typed accessors generated per class via the KSP processor. The class is already opened up via the `klass: KClass<T>` parameter for this purpose.

## Where it's used

- Streaming structured-output consumers in user code (the framework doesn't internally stream `PartiallyGenerated` yet; it's exposed for external pipelines).
- Tests for `constructFromMap` semantics — exercises the same coercion path via the `toComplete` method.

## Related files

- `GenerableSupport.kt` — `constructFromMap` does the actual construction.
- `Annotations.kt` — `@Generable` marks the target class.
- Future: KSP codegen will emit typed property accessors here.
