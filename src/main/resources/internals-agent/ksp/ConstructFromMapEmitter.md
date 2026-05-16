# `agents-kt-ksp/agents_engine/ksp/ConstructFromMapEmitter.kt` — emits `constructFromMap` body

Emits the source body of `constructFromMap(fields: Map<*, Any?>): Foo?` for `@Generable data class` and `@Generable sealed` types (#1704).

## Contract: reproduce runtime behavior byte-for-byte

The generated function must match `GenerableSupport.constructFromMap` exactly:
- **Strict extras rejection (#665)** — if `fields` contains a key not in the type's primary-ctor params, return `null`.
- **Sealed-variant `type` discriminator (#699)** — for sealed roots, read `fields["type"]` and dispatch to the matching variant's emitted constructor.
- **Per-field coercion** — uses the framework's `@PublishedApi` `coerceString` / `coerceInt` / `coerceList` helpers so overflow / type rejection matches reflection byte-for-byte. (E.g., a `Long` value provided for an `Int` field returns `null` exactly when reflection would.)
- **Non-nullable required short-circuit** — return `null` immediately when a coercion call returns `null` for a non-nullable param.

## API

```kotlin
internal object ConstructFromMapEmitter {
    fun canGenerate(cls: GenerableValidator.GenerableClass): Boolean
    fun emitBody(cls: GenerableValidator.GenerableClass): String
}
```

Pure object — no KSP types in the signature. Returns the Kotlin source body that goes inside `constructFromMap(fields: Map<*, Any?>): <Qualified>?`.

## What's emitted

For a clean data class `data class Order(val id: String, val total: Int)`:

```kotlin
fun constructFromMap(fields: Map<*, Any?>): Order? {
    // Strict extras rejection
    val expected = setOf("id", "total")
    if (fields.keys.any { it.toString() !in expected }) return null

    // Per-field coercion
    val id = coerceString(fields["id"]) ?: return null
    val total = coerceInt(fields["total"]) ?: return null

    return Order(id, total)
}
```

For a sealed root, dispatches on `fields["type"]` to the matching variant's `constructFromMap`.

## Scope

`canGenerate(cls)` returns:
- `true` for sealed roots — dispatch doesn't read fields, always generatable.
- `true` for data classes with NO default-valued params.
- `false` for data classes with default-valued params — Kotlin's synthetic constructor-with-mask isn't callable from generated source; those fall through to reflection.

The runtime path's reflection-based `constructFromMap` is the authoritative fallback.

## Helper visibility

The `coerceX` helpers in `agents_engine.generation` are `@PublishedApi internal` — visible to generated code (which lives in `<consumer>.<package>`) precisely because they're marked PublishedApi. The runtime reflection path and the KSP path call the same helpers — that's how byte-identity is guaranteed.

## Related files

- `GenerableValidator.kt` — provides the `GenerableClass` model this consumes.
- `SchemaEmitter.kt`, `LlmDescriptionEmitter.kt` — sibling emitters (different output shapes).
- `generation/GenerableSupport.kt` (main module) — the reflection counterpart.
- `AgentsKtSymbolProcessor.kt` — the orchestrator.
