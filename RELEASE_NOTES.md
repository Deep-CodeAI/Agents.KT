# Agents.KT v0.4.2 — Three Providers + Clean Dependabot

**Release date:** 2026-05-12

Functionally identical to v0.4.1. The only change is making the BouncyCastle 1.84 pin visible to Dependabot so the four false-positive alerts on `main` clear.

## What changed

### BouncyCastle 1.84 declared explicitly (build-only)

The existing `force(...)` block in `build.gradle.kts` already pins BC to 1.84 — confirmed patched by both OSV and GHSA. The lockfile and `gradle/verification-metadata.xml` both record 1.84 as the resolved version. But Dependabot reads the *requested* dependency graph submitted by `gradle/actions/dependency-submission`, not the *resolved* graph, so it kept alerting on the 1.80 vulnerabilities that don't apply.

The fix: declare BC 1.84 explicitly via `compileOnly(...)` at the project level. `compileOnly` does NOT propagate to consumers — `runtimeClasspath` stays free of BC, same as 0.4.1. The only effect is that Dependabot now sees explicit 1.84 nodes in the graph.

```kotlin
dependencies {
    compileOnly("org.bouncycastle:bcprov-jdk18on:1.84")
    compileOnly("org.bouncycastle:bcpg-jdk18on:1.84")
    compileOnly("org.bouncycastle:bcpkix-jdk18on:1.84")
    compileOnly("org.bouncycastle:bcutil-jdk18on:1.84")
}
```

Why we don't drop the `force(...)` block: it's still belt-and-suspenders for any transitive request that bypasses `compileClasspath`.

## Inherited from v0.4.1

(See `RELEASE_NOTES.md` in the v0.4.1 tag for the full notes; same content lands in 0.4.2 unchanged.)

- Three model providers — Ollama, Claude, OpenAI (#1644, #1656)
- LiveRunner precheck hook + `OllamaPreflight` (#1132)
- Live typed-args integration tests across all three providers (#1675)
- `ModelConfig.toString()` masks `apiKey` (#1665)
- Ollama wire-shape fix: `content: null` on assistant tool-call turns (#1694)
- Dependency refresh: `kotlinx-coroutines` 1.11.0, Gradle 9.5.0

## Migration from v0.4.1

Nothing to do. Drop-in upgrade. `runtimeClasspath` is byte-for-byte identical.

## Migration from 0.3.x

Same as 0.4.1 — see [`RELEASE_NOTES.md`](https://github.com/Deep-CodeAI/Agents.KT/blob/v0.4.1/RELEASE_NOTES.md) at the v0.4.1 tag.
