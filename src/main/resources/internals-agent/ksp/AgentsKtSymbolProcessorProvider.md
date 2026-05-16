# `agents-kt-ksp/agents_engine/ksp/AgentsKtSymbolProcessorProvider.kt` — service-loader entry

Two-line factory KSP picks up via service loading.

## Shape

```kotlin
class AgentsKtSymbolProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        AgentsKtSymbolProcessor(environment)
}
```

## How KSP finds it

The KSP plugin scans the consumer's KSP-classpath for:

```
META-INF/services/com.google.devtools.ksp.processing.SymbolProcessorProvider
```

The agents-kt-ksp module's `src/main/resources/META-INF/services/com.google.devtools.ksp.processing.SymbolProcessorProvider` contains one line:

```
agents_engine.ksp.AgentsKtSymbolProcessorProvider
```

Per-round, KSP calls `create(env)` to instantiate the processor and runs `processor.process(resolver)` over the consumer's source tree.

## Consumer wiring

```kotlin
// build.gradle.kts (consumer side)
plugins {
    id("com.google.devtools.ksp") version "..."
}
dependencies {
    implementation("ai.deep-code:agents-kt:<version>")
    ksp("ai.deep-code:agents-kt-ksp:<version>")    // the KSP plugin classpath
}
```

The `ksp(...)` configuration places this JAR on the KSP-only classpath; KSP discovers the provider and the processor runs.

## Why a separate provider class

KSP's `SymbolProcessor` is per-round (it can hold state across rounds via the `process` return value). The provider is the factory KSP calls once per build; it produces fresh processors as needed. Splitting them is the spec.

## Related files

- `AgentsKtSymbolProcessor.kt` — what this provider produces.
- Consumer's `build.gradle.kts` — where the `ksp(...)` dependency is declared.
