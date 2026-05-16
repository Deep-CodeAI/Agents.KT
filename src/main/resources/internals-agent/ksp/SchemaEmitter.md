---
description: Source-file knowledge for agents-kt-ksp/agents_engine/ksp/SchemaEmitter.kt — emits JSON Schema for @Generable data class (#1701). Contract: byte-identical to KClass.dataClassJsonSchema(). Same field ordering, separator placement, @Guide quoting — prompt-cache determinism depends on this. shouldEmit() #1705 defensive gate skips when sealed parent has empty variants list (incremental-compile race). Sealed types out of scope this iteration (separate emitter). Call when the IDE LLM needs to reason about LLM structured-output schemas.
---

# `agents-kt-ksp/agents_engine/ksp/SchemaEmitter.kt` — emits JSON Schema strings

Emits a JSON Schema for a `@Generable` data class (#1701).

## Contract: byte-identical to runtime

Output must match `KClass.dataClassJsonSchema()` in `GenerableSupport` exactly:
- Same field ordering.
- Same separator placement (commas, brackets).
- Same `@Guide` description quoting.

Consumers depend on this for **prompt-cache determinism**: identical input → identical bytes → identical Anthropic cache key → cache hit. Drift between runtime and KSP would invalidate the cache for KSP-enabled consumers vs reflection-only consumers.

## API

```kotlin
internal object SchemaEmitter {
    fun shouldEmit(cls: GenerableValidator.GenerableClass): Boolean
    fun emit(cls: GenerableValidator.GenerableClass): String
}
```

Pure object — no KSP types. The processor builds a `GenerableClass` from `KSClassDeclaration` and passes it here.

## Output shape (data class example)

```json
{
  "type": "object",
  "properties": {
    "id": {"type": "string"},
    "total": {"type": "integer", "description": "amount in cents"}
  },
  "required": ["id", "total"]
}
```

- Object schemas only (no top-level primitives).
- `description` only present when the field has `@Guide`.
- `required` list omits nullable fields.

## `shouldEmit` defensive gate (#1705)

Returns `true` when the processor should emit a `__GeneratedSchema.kt`; `false` to skip (runtime reflection handles it).

The currently-known "skip" case: a sealed parent whose `sealedVariants` list is empty. This usually means KSP saw the parent before all variant files were processed (an incremental-compile race). Emitting `{"oneOf": []}` for a sealed root would produce an invalid schema that the LLM would silently fail on.

Skipping is the safe path: the runtime sees no `__GeneratedSchema`, falls back to reflection, which can resolve sealed variants by reading the bytecode of all sibling classes at runtime.

## Scope

- **Data classes** — in scope, the most common case.
- **Sealed roots** — out of scope this iteration. Variant-with-discriminator shapes are more complex; the runtime path stays authoritative for sealed types.

## Related files

- `GenerableValidator.kt` — provides the `GenerableClass` model.
- `LlmDescriptionEmitter.kt`, `ConstructFromMapEmitter.kt` — sibling emitters.
- `generation/GenerableSupport.kt` (main module) — the reflection counterpart whose output this matches.
- `AgentsKtSymbolProcessor.kt` — the orchestrator that calls this per round.
