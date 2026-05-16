---
description: Source-file knowledge for agents-kt-ksp/agents_engine/ksp/AgentsKtSymbolProcessor.kt — KSP processor entry (#1018). Two passes per round: validation via GenerableValidator (#1700), then schema/description/constructor emission via SchemaEmitter/LlmDescriptionEmitter/ConstructFromMapEmitter (#1701/#1703/#1704). Emits <package>/<ClassName>__GeneratedSchema.kt; runtime GenerableSupport reads via Class.forName, falls back to reflection. Sealed roots and default-valued-param classes fall through to reflection. Call when the IDE LLM needs to reason about KSP-vs-reflection dispatch.
---

# `agents-kt-ksp/agents_engine/ksp/AgentsKtSymbolProcessor.kt` — KSP processor entry

The KSP `SymbolProcessor` impl. Discovered via the service-loader provider; runs over the consumer's source tree at compile time.

## Two passes per processor round

### Pass 1: validation (#1700)

Walks every `@Generable` class. For each:
- Calls `GenerableValidator.validate(model)` with a pure-data shape descriptor.
- Reports rule violations via `env.logger.error(message, symbol)` — surfaces in the IDE / `kotlinc` output.

Validation rules check class shape (concrete data class or sealed root), constructor presence, every primary-ctor param's type being a supported `@Generable`-friendly type, etc.

### Pass 2: schema generation (#1701, #1703, #1704)

For each `@Generable` class that passes validation AND is "clean non-sealed data class":
- Generates `<package>/<ClassName>__GeneratedSchema.kt` via `env.codeGenerator.createNewFile(...)`.
- Inside, an `object <ClassName>__GeneratedSchema` carrying:
  - `const val JSON_SCHEMA: String` via `SchemaEmitter.emit(...)`.
  - `const val LLM_DESCRIPTION: String` via `LlmDescriptionEmitter.emit(...)`.
  - `fun constructFromMap(fields: Map<*, Any?>): T?` via `ConstructFromMapEmitter.emitBody(...)`.

The runtime in `GenerableSupport.GeneratedMetaCache` looks these up via `Class.forName("...__GeneratedSchema")` — when present, KSP wins; when absent, reflection takes over. Both paths produce byte-identical output (cache determinism).

## What's NOT generated

- **Sealed roots** — variant-with-discriminator schemas are more complex and go through a separate emitter; the runtime path stays authoritative for now.
- **Classes with default-valued params** — `ConstructFromMapEmitter.canGenerate(...)` returns false because Kotlin's synthetic constructor-with-mask isn't callable from generated source. Those fall through to the runtime reflection path.
- **Validation-failing classes** — codegen is skipped after `validate(...)` reports errors.

## Output discovery in the consumer

```
src/main/kotlin/com/example/Order.kt          (consumer's @Generable type)
build/generated/ksp/main/kotlin/com/example/Order__GeneratedSchema.kt   (emitted)
```

The package mirrors the source. Files are emitted with `Dependencies(aggregating = false, ...)` so incremental compilation reacts to changes correctly.

## `process(resolver)` shape

```kotlin
override fun process(resolver: Resolver): List<KSAnnotated> {
    val generables = resolver
        .getSymbolsWithAnnotation(GENERABLE_FQN)
        .filterIsInstance<KSClassDeclaration>()
    for (cls in generables) {
        val model = cls.toGenerableClass(resolver)        // KSP → pure-data
        val errors = GenerableValidator.validate(model)
        errors.forEach { env.logger.error(it, cls) }
        if (errors.isEmpty()) emitCodegen(cls, model)
    }
    return emptyList()    // no deferred symbols
}
```

## Related files

- `AgentsKtSymbolProcessorProvider.kt` — service-loader entry.
- `GenerableValidator.kt` — pure-data validation rules.
- `SchemaEmitter.kt`, `LlmDescriptionEmitter.kt`, `ConstructFromMapEmitter.kt` — the three emitters.
- `generation/GenerableSupport.kt` (main module) — the runtime consumer of the emitted code.
- `generation/Annotations.kt` (main module) — the `@Generable` annotation discovered here.
