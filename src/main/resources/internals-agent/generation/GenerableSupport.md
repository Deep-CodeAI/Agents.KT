---
description: Source-file knowledge for agents_engine/generation/GenerableSupport.kt — runtime support for @Generable. Three surfaces (jsonSchema, toLlmDescription, constructFromMap/fromLlmOutput/toLlmInput). Two-path dispatch: KSP-generated __GeneratedSchema lookup first (#1701-#1704 zero-reflection), reflection fallback otherwise. ConcurrentHashMap caching with MISS sentinel. Sealed-interface discriminator handling. Call when the IDE LLM needs to reason about typed structured-output coercion.
---

# `agents_engine/generation/GenerableSupport.kt` — runtime support for `@Generable`

The big workhorse file behind `@Generable`. Three public surfaces (`jsonSchema`, `toLlmDescription`, `constructFromMap`), each with a KSP-generated fast path and a reflection fallback.

## Public surfaces

```kotlin
fun KClass<*>.jsonSchema(): String                           // JSON Schema for the LLM's tool-call shape
fun KClass<*>.toLlmDescription(): String                     // Human-readable description embedded in prompts
fun <T : Any> KClass<T>.constructFromMap(args: Map<*, Any?>): T?   // Map (from LLM tool call) → typed instance
fun <T : Any> KClass<T>.fromLlmOutput(rawText: String): T?   // Convenience: parse JSON + construct
fun <T : Any> T.toLlmInput(): String                         // Reverse: serialize a typed value to LLM-input JSON
```

## Two paths: KSP first, reflection fallback

```
                ┌── lookupJsonSchema(kClass) ──┐
KClass.jsonSchema() ──┤                              ├── reflection-based schema generation
                └── KSP cache hit               (when kotlin-reflect is on classpath)
                    (no reflection needed)
```

`GeneratedMetaCache` (private object) looks up `<ClassName>__GeneratedSchema` per `KClass`. If KSP emitted the metadata at compile time, the lookup returns the byte-identical constant. If not, the call falls through to `ReflectionFallback.withReflection { ... }` which either returns the reflectively-computed value or `null` (when `kotlin-reflect` is also unavailable).

This is what lets `agents-kt` declare `kotlin-reflect` as `compileOnly` — consumers either:
1. Apply `:agents-kt-ksp` (recommended) — generated path covers everything.
2. Add `kotlin-reflect` to their own dependencies for the reflection fallback.
3. Neither — schema/description lookups return null; typed-tool deserialization routes through `onError.invalidArgs`; upstream code surfaces a clear "couldn't resolve" error rather than crashing.

## `constructFromMap`

The reverse of `jsonSchema` — coerce a `Map<*, Any?>` (typically from `LenientJsonParser.parse(llmText)`) into a typed `@Generable` instance.

Algorithm:
1. Try `GeneratedMetaCache.lookupConstructor(kClass)` — if KSP emitted `constructFromMap`, invoke it.
2. Else, reflect: find the primary constructor, match each `KParameter` by name to a map entry, coerce values using the parameter's `KType`.
3. Handle nested `@Generable` types recursively (a `LineItem` inside `Order.items` gets its own `constructFromMap` call).
4. Handle sealed interface variants via the `_type` discriminator field.
5. On failure (missing required param, type coercion fails, constructor throws) → return `null`.

`null` return semantics are documented at the call site: the caller decides whether `null` is recoverable (`onError.deserializationError`) or fatal.

## Caching

`GeneratedMetaCache` uses `ConcurrentHashMap<KClass<*>, Entry>` — the typical multi-thread agentic-loop access pattern. Each entry caches both the generated constants map AND the generated constructor `Method`. Cache misses are cached too (`MISS` sentinel) so repeated lookups for non-`@Generable` classes don't repeatedly call `Class.forName`.

## Reflection-fallback safety

Every reflection-using line is wrapped:

```kotlin
ReflectionFallback.withReflection {
    primaryConstructor?.parameters?.mapNotNull { ... }
} ?: emptyList()
```

`withReflection` catches `LinkageError` and `KotlinReflectionNotSupportedError`. Real exceptions (NPE in user code, custom validation throws) propagate so they are not silently masked.

## Related files

- `Annotations.kt` — the three annotations consumed.
- `LenientJsonParser.kt` — parses LLM text into the `Map` `constructFromMap` consumes.
- `PartiallyGenerated.kt` — streaming accumulator that also calls `constructFromMap`.
- `ReflectionFallback.kt` — the wrapper for graceful degradation.
- `:agents-kt-ksp` — generates the `__GeneratedSchema` companions.
- `SkillRoute.kt`, every typed `Tool<Args, _>` — heavy users of these surfaces.
