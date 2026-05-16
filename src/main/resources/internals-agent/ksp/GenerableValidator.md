# `agents-kt-ksp/agents_engine/ksp/GenerableValidator.kt` — compile-time `@Generable` rules

Pure-data shape rules for `@Generable` classes (#1700). Lifts diagnosis to the IDE / compile step.

## Why KSP-free

The KSP test harness (kctfork) lags Kotlin metadata versions; the project runs on Kotlin 2.3.x. By making the validator pure-data — `GenerableClass` is a tiny data class with no KSP types — the rules can be unit-tested with plain JUnit, no KSP harness required.

The `AgentsKtSymbolProcessor` does the KSP → `GenerableClass` extraction; this object only sees the extracted shape.

## API

```kotlin
internal object GenerableValidator {
    internal data class GenerableClass(
        val qualifiedName: String,
        val isSealed: Boolean,
        val isAbstract: Boolean,
        val isInterface: Boolean,
        val isEnum: Boolean,
        val isAnnotation: Boolean,
        val hasPrimaryConstructor: Boolean,
        val primaryConstructorParamCount: Int,
        val fields: List<Field>,
        val sealedVariants: List<GenerableClass>,
        // ...
    )

    fun validate(cls: GenerableClass): List<String>   // error messages, empty list = valid
}
```

## Rules (sample)

- **Shape:** must be `data class` OR sealed root. Plain classes, interfaces, enums, annotations are rejected.
- **Construction:** must have a primary constructor with at least one param.
- **Field types (#1701):** every primary-ctor param must be a supported type — primitives, `String`, other `@Generable` types, `List<T>` of supported, `Map<String, V>` of supported.
- **Sealed roots:** every variant must itself be `@Generable`.
- **Nullable handling:** nullable params are allowed; the schema marks them not-required.

The full ruleset evolves as new types are added — keep an eye on the `validate(...)` body for the authoritative list.

## Error messages

Each rule violation appends a string like:

> `Person.kt: @Generable type Person.age has unsupported type java.util.Date — use String, Long, or a @Generable wrapper.`

Strings are passed verbatim to `env.logger.error(msg, symbol)` in the processor — the symbol gives KSP the source location for IDE underlines.

## Tests

`agents-kt-ksp/src/test/kotlin/agents_engine/ksp/GenerableValidatorTest.kt` covers every rule with hand-built `GenerableClass` instances. No KSP harness needed.

## Related files

- `AgentsKtSymbolProcessor.kt` — calls `validate(...)` per `@Generable`.
- `SchemaEmitter.kt`, `LlmDescriptionEmitter.kt`, `ConstructFromMapEmitter.kt` — emitters that consume the same `GenerableClass` shape.
- `generation/Annotations.kt` (main module) — the `@Generable` annotation.
- `generation/GenerableSupport.kt` (main module) — the runtime path whose contract these rules enforce.
