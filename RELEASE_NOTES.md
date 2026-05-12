# Agents.KT v0.4.3 — Complete BC Pin Across Both Modules

**Release date:** 2026-05-12

Functionally identical to v0.4.2. Consumer-visible POMs and `runtimeClasspath` are byte-for-byte the same. The only change is finishing what v0.4.2 started — pinning BouncyCastle 1.84 in the `:agents-kt-ksp` subproject too, so all four dependabot advisories can finally clear.

## What changed since v0.4.2

v0.4.2 added explicit `compileOnly("org.bouncycastle:*:1.84")` declarations + a `force(...)` block to the **root** `build.gradle.kts`, giving Dependabot visible 1.84 nodes in the root project's submitted graph.

But the **`:agents-kt-ksp` subproject** never got the same treatment. Its `kotlinBouncyCastleConfiguration` kept resolving to BC 1.80 transitively (Kotlin Gradle plugin pulls it for jar signing), and Dependabot's per-subproject graph submission kept showing 1.80, keeping the four advisories alive.

v0.4.3 mirrors the v0.4.2 fix into `agents-kt-ksp/build.gradle.kts`:

```kotlin
// agents-kt-ksp/build.gradle.kts
configurations.all {
    resolutionStrategy {
        force(
            "org.bouncycastle:bcprov-jdk18on:1.84",
            "org.bouncycastle:bcpg-jdk18on:1.84",
            "org.bouncycastle:bcpkix-jdk18on:1.84",
            "org.bouncycastle:bcutil-jdk18on:1.84",
        )
    }
}

dependencies {
    // ... existing deps ...
    compileOnly("org.bouncycastle:bcprov-jdk18on:1.84")
    compileOnly("org.bouncycastle:bcpg-jdk18on:1.84")
    compileOnly("org.bouncycastle:bcpkix-jdk18on:1.84")
    compileOnly("org.bouncycastle:bcutil-jdk18on:1.84")
}
```

Stale BC 1.80 entries in `gradle/verification-metadata.xml` (cumulative cruft from the original 1.80 era) were also pruned — only the 1.84 checksums remain.

After v0.4.3 lands on `main`, the next `gradle/actions/dependency-submission` workflow run submits a clean 1.84-everywhere graph for both modules, and the four dependabot alerts should clear.

## Inherited from v0.4.2

Same as v0.4.2 — see the v0.4.2 release notes for the full feature set since 0.3.0:

- Three model providers — Ollama, Claude, OpenAI (#1644, #1656)
- LiveRunner precheck hook + `OllamaPreflight` (#1132)
- Live typed-args integration tests across all three providers (#1675)
- `ModelConfig.toString()` masks `apiKey` (#1665)
- Ollama wire-shape fix: `content: null` on assistant tool-call turns (#1694)
- Dependency refresh: `kotlinx-coroutines` 1.11.0, Gradle 9.5.0
- BC 1.84 pinned visibly in root module (#1695, v0.4.2)

## Migration from v0.4.2

Drop-in. Nothing to do.

## Migration from 0.3.x

```kotlin
implementation("ai.deep-code:agents-kt:0.4.3")
```

(Or skip directly from 0.3.x to 0.4.3; v0.4.0, v0.4.1, and v0.4.2 details are in their own release notes if you want the breadcrumb trail.)
